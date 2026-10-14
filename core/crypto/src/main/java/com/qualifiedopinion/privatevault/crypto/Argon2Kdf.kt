package com.qualifiedopinion.privatevault.crypto

import com.lambdapioneer.argon2kt.Argon2Exception
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.nio.ByteBuffer

/** Argon2id v1.3 through native `argon2kt` (planning pack §4.3). Android only. */
class Argon2Kdf(private val argon2: Argon2Kt = Argon2Kt()) : PinKdf {

    override fun deriveKey(pin: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val result = try {
            argon2.hash(
                Argon2Mode.ARGON2_ID,
                pin,
                salt,
                params.iterations,
                params.memoryKiB,
                params.parallelism,
                PinKdf.KEY_SIZE,
                Argon2Version.V13,
            )
        } catch (e: Argon2Exception) {
            // Native malloc failure is reported as an Argon2Exception, not an OutOfMemoryError.
            val msg = e.message.orEmpty()
            if ("MEMORY_ALLOCATION" in msg || "MALLOC" in msg) throw OutOfMemoryError("argon2: $msg")
            throw e
        }
        return try {
            result.rawHashAsByteArray()
        } finally {
            // Both direct buffers hold the hash (raw and encoded); wipe them.
            wipe(result.rawHash)
            wipe(result.encodedOutput)
        }
    }

    private fun wipe(buf: ByteBuffer) {
        if (buf.isReadOnly) return
        val d = buf.duplicate()
        d.clear()
        while (d.hasRemaining()) d.put(0)
    }

    /** Calibrates on this device; see [KdfCalibrator]. Runs for several seconds: call off the main thread. */
    fun calibrate(): KdfParams = KdfCalibrator(this).calibrate()
}
