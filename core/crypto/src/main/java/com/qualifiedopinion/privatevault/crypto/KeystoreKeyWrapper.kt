package com.qualifiedopinion.privatevault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.qualifiedopinion.privatevault.format.toHex
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Outer wrap layer with a non-exportable Android Keystore AES-256-GCM key (planning pack §4.2).
 *
 * Blob format: `iv(12) ‖ ciphertext ‖ tag(16)`. The Keystore picks the IV (randomized encryption).
 * The key needs no user authentication (no biometrics in the MVP; the device may have no lock screen)
 * and does not ask for StrongBox (old phone).
 */
class KeystoreKeyWrapper(val alias: String) : KeyWrapper {

    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv.size != IV_SIZE) throw GeneralSecurityException("unexpected GCM IV length ${iv.size}")
        return iv + ct
    }

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < IV_SIZE + TAG_SIZE) throw GeneralSecurityException("wrapped blob too short")
        val key = existingKey() ?: throw KeyWrapperKeyMissingException("no Keystore key for this vault")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * Byte.SIZE_BITS, blob, 0, IV_SIZE))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    fun keyExists(): Boolean = keyStore().containsAlias(alias)

    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    /** False on a software-only Keystore; PR7 warns at setup (§4.2). */
    @Suppress("DEPRECATION") // KeyInfo.securityLevel is API 31+; isInsideSecureHardware works on 26+.
    fun isInsideSecureHardware(): Boolean {
        val key = existingKey() ?: return false
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.isInsideSecureHardware
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val ALIAS_PREFIX = "pv_kek_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private const val KEY_SIZE_BITS = 256

        /** The alias is derived from the vault id and never stored (§3.3). */
        fun aliasFor(vaultId: ByteArray): String = ALIAS_PREFIX + vaultId.toHex()

        fun forVault(vaultId: ByteArray): KeystoreKeyWrapper = KeystoreKeyWrapper(aliasFor(vaultId))
    }
}
