package com.qualifiedopinion.privatevault.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.AesGcmJce
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.VaultMeta
import com.qualifiedopinion.privatevault.format.VaultMetaCodec
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Layered wrap of the data keyset, `KS(PIN(DK))` (planning pack §4.2):
 *
 * 1. inner = Tink encrypted keyset of DK under `AesGcmJce(KEK_pin)`, KEK_pin = Argon2id(PIN, salt, params)
 * 2. outer = [KeyWrapper] (Android Keystore AES-GCM) over inner
 *
 * Both layers use AAD = `vault.meta[0,60)` (header + KDF parameters + salt + scheme).
 *
 * PIN arrays passed in are zeroized before these functions return, as is KEK_pin.
 */
class LayeredKeyWrap(
    private val kdf: PinKdf,
    private val keyWrapperFor: (vaultId: ByteArray) -> KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        TinkRegistration.ensure()
    }

    /** Builds a complete `vault.meta`. [params] must meet the floor and [pin] must be 6–12 digits. */
    fun wrap(vaultId: ByteArray, dk: KeysetHandle, pin: ByteArray, params: KdfParams): ByteArray {
        try {
            require(Pins.isValid(pin)) { "PIN must be ${Pins.MIN_LENGTH}-${Pins.MAX_LENGTH} digits" }
            require(params.meetsFloor()) { "KDF parameters below the floor" }
            VaultCrypto.requireDataKey(dk)
            val header = FileHeader(FileType.VAULT_META, vaultId)
            val salt = ByteArray(PinKdf.SALT_SIZE).also(random::nextBytes)
            val aad = VaultMetaCodec.aad(header, params, salt)
            val kek = kdf.deriveKey(pin, salt, params)
            val inner = try {
                TinkProtoKeysetFormat.serializeEncryptedKeyset(dk, AesGcmJce(kek), aad, RegistryConfiguration.get())
            } finally {
                kek.fill(0)
            }
            val outer = keyWrapperFor(vaultId).wrap(inner, aad)
            return VaultMetaCodec.encode(VaultMeta(header, params, salt, outer))
        } finally {
            pin.fill(0)
        }
    }

    fun wrap(vaultId: ByteArray, dk: KeysetHandle, pin: CharArray, params: KdfParams): ByteArray =
        try {
            wrap(vaultId, dk, Pins.toBytes(pin), params)
        } finally {
            pin.fill('\u0000')
        }

    /**
     * Opens `vault.meta` and returns the DK.
     *
     * @throws com.qualifiedopinion.privatevault.format.VaultFormatException malformed or unknown format
     * @throws KeyWrapperKeyMissingException the Keystore key is gone (reinstall / new phone)
     * @throws WrongPinException the inner layer failed: wrong PIN (or a forged inner blob)
     * @throws GeneralSecurityException the outer layer failed (tampered file or wrong device)
     */
    fun unwrap(vaultMeta: ByteArray, pin: ByteArray): KeysetHandle {
        try {
            val meta = VaultMetaCodec.decode(vaultMeta)
            val aad = vaultMeta.copyOfRange(0, VaultMetaCodec.AAD_LENGTH)
            val inner = keyWrapperFor(meta.vaultId).unwrap(meta.outerBlob, aad)
            val kek = kdf.deriveKey(pin, meta.salt, meta.kdf)
            val dk = try {
                TinkProtoKeysetFormat.parseEncryptedKeyset(inner, AesGcmJce(kek), aad, RegistryConfiguration.get())
            } catch (e: GeneralSecurityException) {
                throw WrongPinException(e)
            } finally {
                kek.fill(0)
            }
            VaultCrypto.requireDataKey(dk)
            return dk
        } finally {
            pin.fill(0)
        }
    }

    fun unwrap(vaultMeta: ByteArray, pin: CharArray): KeysetHandle =
        try {
            unwrap(vaultMeta, Pins.toBytes(pin))
        } finally {
            pin.fill('\u0000')
        }
}

/** The PIN layer did not authenticate. Counts as a failed attempt for throttling (PR7). */
class WrongPinException(cause: Throwable) : GeneralSecurityException("wrong PIN", cause)
