package com.qualifiedopinion.privatevault.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software AES-256-GCM stand-in for the Keystore wrapper (same blob format: iv ‖ ct ‖ tag). */
class SoftwareKeyWrapper(private val key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : KeyWrapper {
    private val random = SecureRandom()

    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plaintext)
    }

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 28) throw GeneralSecurityException("short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Pure-Java Argon2id v1.3 (JVM tests only; production uses native argon2kt). */
class BouncyCastleArgon2Kdf : PinKdf {
    override fun deriveKey(pin: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val g = Argon2BytesGenerator()
        g.init(p)
        val out = ByteArray(PinKdf.KEY_SIZE)
        g.generateBytes(pin, out)
        return out
    }
}

/** Fast deterministic KDF for tests that only need "same inputs, same key". */
class FakeKdf : PinKdf {
    val issuedKeys = mutableListOf<ByteArray>()
    val seenPins = mutableListOf<ByteArray>()

    override fun deriveKey(pin: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        seenPins += pin
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pin)
        md.update(salt)
        md.update(ByteBuffer.allocate(12).putInt(params.memoryKiB).putInt(params.iterations).putInt(params.parallelism))
        return md.digest().also { issuedKeys += it }
    }
}
