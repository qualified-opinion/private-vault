package com.qualifiedopinion.privatevault.vault

import com.google.crypto.tink.KeysetHandle
import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.KeyWrapper
import com.qualifiedopinion.privatevault.crypto.LayeredKeyWrap
import com.qualifiedopinion.privatevault.crypto.PinKdf
import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import com.qualifiedopinion.privatevault.format.FileHeader

/**
 * Creates and opens vaults with PR2's layered wrap, `KS(PIN(DK))` (planning pack §4.2).
 *
 * Production passes `Argon2Kdf()` and `KeystoreKeyWrapper::forVault`; JVM tests pass fakes.
 * PIN arrays are zeroized by these calls (see [LayeredKeyWrap]); callers must not reuse them.
 * Both calls are slow (Argon2id): run them off the main thread.
 */
class VaultKeys(kdf: PinKdf, keyWrapperFor: (vaultId: ByteArray) -> KeyWrapper) {
    private val wrap = LayeredKeyWrap(kdf, keyWrapperFor)

    /**
     * Creates a new vault in [files] (which must not hold one) and starts [session] on it.
     * Returns the new vault id.
     *
     * @throws VaultAlreadyExistsException the folder already has a vault; nothing is written
     * @throws VaultReadOnlyException the folder is read-only
     */
    fun create(files: VaultFiles, pin: ByteArray, params: KdfParams, session: VaultSession): ByteArray {
        try {
            files.requireWritable()
            if (files.hasVault()) throw VaultAlreadyExistsException()
            val vaultId = FileHeader.newId()
            val dk = VaultCrypto.newDataKeyset()
            files.createVault(wrap.wrap(vaultId, dk, pin, params))
            session.unlock(dk, vaultId)
            return vaultId
        } finally {
            pin.fill(0)
        }
    }

    /**
     * Unwraps the DK from the folder's `vault.meta` (or its `.bak`, §3.3) and starts [session].
     * If [expectedVaultId] is given, a different vault is rejected before any key is touched.
     *
     * @throws VaultIdMismatchException the folder holds another vault
     * @throws com.qualifiedopinion.privatevault.crypto.WrongPinException wrong PIN
     * @throws com.qualifiedopinion.privatevault.crypto.KeyWrapperKeyMissingException the Keystore key is gone (PR8)
     */
    fun unlock(files: VaultFiles, pin: ByteArray, expectedVaultId: ByteArray?, session: VaultSession): ByteArray {
        try {
            val stored = if (expectedVaultId != null) files.requireVaultId(expectedVaultId) else files.readVaultMeta()
            val dk: KeysetHandle = wrap.unwrap(stored.bytes, pin)
            val vaultId = stored.meta.vaultId
            session.unlock(dk, vaultId)
            return vaultId
        } finally {
            pin.fill(0)
        }
    }
}
