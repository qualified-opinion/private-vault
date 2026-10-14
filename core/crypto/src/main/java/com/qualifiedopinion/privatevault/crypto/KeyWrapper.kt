package com.qualifiedopinion.privatevault.crypto

import java.security.GeneralSecurityException

/**
 * The outer layer of the DK wrap (planning pack §4.2). Production is [KeystoreKeyWrapper];
 * JVM tests use a software AES-GCM fake.
 *
 * Implementations must be authenticated encryption that binds [aad]: any change to the blob or
 * the AAD makes [unwrap] throw [GeneralSecurityException].
 */
interface KeyWrapper {
    fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray

    fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray
}

/** The Keystore key for this vault does not exist (uninstall, clear data or a new phone). Restore needs PR8. */
class KeyWrapperKeyMissingException(message: String) : GeneralSecurityException(message)
