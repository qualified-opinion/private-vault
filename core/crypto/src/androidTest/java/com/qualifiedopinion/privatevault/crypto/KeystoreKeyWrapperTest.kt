package com.qualifiedopinion.privatevault.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.toHex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Random

/**
 * On-device checks for PR2 (planning pack §6 PR2, risks 1, 8, 9). Run on an emulator and on the target phone:
 * `./gradlew :core:crypto:connectedDebugAndroidTest`, then `adb logcat -s PV-CRYPTO` for the numbers.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyWrapperTest {
    private val vaultId = FileHeader.newId()
    private val wrapper = KeystoreKeyWrapper.forVault(vaultId)

    @After
    fun deleteKey() {
        wrapper.deleteKey()
    }

    @Test
    fun keystoreWrapRoundTripsWithRandomIv() {
        val secret = ByteArray(200) { it.toByte() }
        val aad = ByteArray(60) { 7 }
        val a = wrapper.wrap(secret, aad)
        val b = wrapper.wrap(secret, aad)
        assertFalse("randomized IV", a.contentEquals(b))
        assertEquals(12 + secret.size + 16, a.size)
        assertArrayEquals(secret, wrapper.unwrap(a, aad))
        assertArrayEquals(secret, wrapper.unwrap(b, aad))
        assertEquals("pv_kek_" + vaultId.toHex(), wrapper.alias)
        Log.i(TAG, "Keystore key inside secure hardware: ${wrapper.isInsideSecureHardware()}")
    }

    @Test
    fun keystoreWrapRejectsTamperingAndMissingKey() {
        val aad = ByteArray(60) { 1 }
        val blob = wrapper.wrap(ByteArray(32) { 5 }, aad)
        val otherAad = aad.copyOf().also { it[59] = 2 }
        assertThrows(GeneralSecurityException::class.java) { wrapper.unwrap(blob, otherAad) }
        val flipped = blob.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { wrapper.unwrap(flipped, aad) }

        wrapper.deleteKey()
        assertFalse(wrapper.keyExists())
        assertThrows(KeyWrapperKeyMissingException::class.java) { wrapper.unwrap(blob, aad) }
    }

    @Test
    fun argon2ktMatchesReferenceVectors() {
        val kdf = Argon2Kdf()
        // phc-winner-argon2 Argon2id v1.3 vectors, "password"/"somesalt", t=2, p=1. Same values as the JVM test.
        val small = kdf.deriveKey("password".toByteArray(), "somesalt".toByteArray(), KdfParams(256, 2, 1))
        assertEquals("9dfeb910e80bad0311fee20f9c0e2b12c17987b4cac90c2ef54d5b3021c68bfe", small.toHex())
        val big = kdf.deriveKey("password".toByteArray(), "somesalt".toByteArray(), KdfParams(65536, 2, 1))
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", big.toHex())
    }

    @Test
    fun layeredWrapWithKeystoreAndArgon2kt() {
        val dk = VaultCrypto.newDataKeyset()
        val wrap = LayeredKeyWrap(Argon2Kdf(), KeystoreKeyWrapper::forVault)
        val meta = wrap.wrap(vaultId, dk, "135790".toCharArray(), KdfParams.FLOOR)
        val t0 = System.nanoTime()
        assertTrue(dk.equalsKeyset(wrap.unwrap(meta, "135790".toCharArray())))
        Log.i(TAG, "unwrap at floor params: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertThrows(WrongPinException::class.java) { wrap.unwrap(meta, "135791".toCharArray()) }
    }

    @Test
    fun calibrationStaysAtOrAboveTheFloor() {
        val kdf = Argon2Kdf()
        val t0 = System.nanoTime()
        val params = kdf.calibrate()
        val calMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        kdf.deriveKey("123456".toByteArray(), ByteArray(16), params).fill(0)
        val ms = (System.nanoTime() - t1) / 1_000_000
        Log.i(TAG, "calibrated $params: one derivation $ms ms (calibration took $calMs ms)")
        assertTrue(params.meetsFloor())
    }

    @Test
    fun streamingThroughput() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "pv-throughput.bin") // ciphertext only; deleted below
        val crypto = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
        val header = FileHeader(FileType.DATA, FileHeader.newId())
        val size = 64L * 1024 * 1024
        val chunk = ByteArray(256 * 1024)
        val rnd = Random(1)
        try {
            val inHash = MessageDigest.getInstance("SHA-256")
            val t0 = System.nanoTime()
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { fc ->
                crypto.newEncryptingChannel(fc, header).use { enc ->
                    var left = size
                    while (left > 0) {
                        rnd.nextBytes(chunk)
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        inHash.update(chunk, 0, n)
                        val buf = ByteBuffer.wrap(chunk, 0, n)
                        while (buf.hasRemaining()) enc.write(buf)
                        left -= n
                    }
                }
            }
            val t1 = System.nanoTime()
            val outHash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                crypto.newDecryptingStream(input, header).use { s ->
                    while (true) {
                        val n = s.read(chunk)
                        if (n < 0) break
                        outHash.update(chunk, 0, n)
                    }
                }
            }
            val t2 = System.nanoTime()
            assertArrayEquals(inHash.digest(), outHash.digest())
            val mib = size / (1024 * 1024)
            Log.i(TAG, "64 MiB encrypt+write ${(t1 - t0) / 1_000_000} ms, read+decrypt ${(t2 - t1) / 1_000_000} ms ($mib MiB)")
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val TAG = "PV-CRYPTO"
    }
}
