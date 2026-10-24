package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.KeyWrapper
import com.qualifiedopinion.privatevault.crypto.PinKdf
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.VaultMeta
import com.qualifiedopinion.privatevault.format.VaultMetaCodec
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A temp folder under the Gradle test task's temp dir (see build.gradle.kts). */
fun vaultTempFolder(): TemporaryFolder =
    TemporaryFolder(File(System.getProperty("pv.tmpDir") ?: System.getProperty("java.io.tmpdir")))

/** Software AES-GCM stand-in for the Keystore layer, one key per "device". */
class SoftwareKeyWrapper(private val key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : KeyWrapper {
    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
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

/** Fast deterministic KDF ("same inputs, same key"); Argon2id itself is tested in :core:crypto. */
class FakeKdf : PinKdf {
    override fun deriveKey(pin: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pin)
        md.update(salt)
        md.update(ByteBuffer.allocate(12).putInt(params.memoryKiB).putInt(params.iterations).putInt(params.parallelism))
        return md.digest()
    }
}

fun pin(digits: String): ByteArray = digits.toByteArray(Charsets.US_ASCII)

/** A syntactically valid vault.meta with a random outer blob (enough for layout tests). */
fun fakeVaultMeta(vaultId: ByteArray = FileHeader.newId(), blobSize: Int = 100): ByteArray {
    val blob = ByteArray(blobSize).also(SecureRandom()::nextBytes)
    val salt = ByteArray(PinKdf.SALT_SIZE).also(SecureRandom()::nextBytes)
    return VaultMetaCodec.encode(VaultMeta(FileHeader(FileType.VAULT_META, vaultId), KdfParams.FLOOR, salt, blob))
}

/**
 * Wraps a storage and throws [SimulatedCrash] at the [failAt]-th mutating call (0-based) and at every
 * mutating call after it, leaving the disk as it was before that call. Simulates the process dying
 * (or the card going) mid-sequence: no cleanup code gets to run either.
 */
class CrashingStorage(private val inner: VaultStorage, private val failAt: Int) : VaultStorage by inner {
    var mutations = 0
        private set

    private fun step() {
        if (mutations++ >= failAt) throw SimulatedCrash()
    }

    override fun create(dir: String, name: String): FileChannel {
        step()
        return inner.create(dir, name)
    }

    override fun rename(dir: String, from: String, to: String) {
        step()
        inner.rename(dir, from, to)
    }

    override fun move(fromDir: String, name: String, toDir: String) {
        step()
        inner.move(fromDir, name, toDir)
    }

    override fun delete(dir: String, name: String): Boolean {
        step()
        return inner.delete(dir, name)
    }

    override fun ensureDir(dir: String) {
        step()
        inner.ensureDir(dir)
    }
}

class SimulatedCrash : IOException("simulated crash")

fun File.child(vararg parts: String): File = parts.fold(this) { f, p -> File(f, p) }
