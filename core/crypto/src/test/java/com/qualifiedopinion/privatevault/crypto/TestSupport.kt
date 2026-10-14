package com.qualifiedopinion.privatevault.crypto

import com.qualifiedopinion.privatevault.format.FileHeader
import org.junit.Assert.fail
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException

/** Deterministic plaintext: byte i is byte (i and 7) (little-endian) of splitmix64(i ushr 3). */
object Gen {
    private val GOLDEN = 0x9E3779B97F4A7C15UL.toLong()
    private val MIX1 = 0xBF58476D1CE4E5B9UL.toLong()
    private val MIX2 = 0x94D049BB133111EBUL.toLong()

    /** The n-th output of a splitmix64 generator seeded with 0. */
    fun splitmix64(n: Long): Long {
        var z = (n + 1) * GOLDEN
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    fun byteAt(i: Long): Byte = (splitmix64(i ushr 3) ushr ((i and 7).toInt() * 8)).toByte()

    /** Fills dst[0, len) with byteAt(start + j); one splitmix64 per 8 bytes. */
    fun fill(dst: ByteArray, len: Int, start: Long) {
        var i = start
        var j = 0
        while (j < len) {
            val v = splitmix64(i ushr 3)
            var b = (i and 7).toInt()
            while (b < 8 && j < len) {
                dst[j++] = (v ushr (b * 8)).toByte()
                b++
                i++
            }
        }
    }
}

object TestFiles {
    /** Gradle passes the test task's temp dir; fall back to java.io.tmpdir in an IDE. */
    val dir: File by lazy {
        File(System.getProperty("pv.tmpDir") ?: System.getProperty("java.io.tmpdir")).also { it.mkdirs() }
    }

    fun newFile(prefix: String): File = File.createTempFile(prefix, ".bin", dir).also { it.deleteOnExit() }
}

val SEG = VaultCrypto.PLAINTEXT_SEGMENT_SIZE.toLong()
val FIRST_SEG = VaultCrypto.FIRST_SEGMENT_PLAINTEXT_SIZE.toLong()
private const val CHUNK = 64 * 1024

/** Encrypts byteAt(0 until size) into [file] in 64 KiB chunks. */
fun VaultCrypto.encryptGenerated(file: File, header: FileHeader, size: Long) {
    FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { fc ->
        newEncryptingChannel(fc, header).use { enc -> writeGenerated(enc, size) }
    }
}

private fun writeGenerated(enc: WritableByteChannel, size: Long) {
    val chunk = ByteArray(CHUNK)
    var pos = 0L
    while (pos < size) {
        val n = minOf(CHUNK.toLong(), size - pos).toInt()
        Gen.fill(chunk, n, pos)
        val buf = ByteBuffer.wrap(chunk, 0, n)
        while (buf.hasRemaining()) enc.write(buf)
        pos += n
    }
}

/** Reads until [dst] is full or EOF. Returns bytes read, or -1 if EOF came first. */
fun ReadableByteChannel.readFully(dst: ByteBuffer): Int {
    var total = 0
    while (dst.hasRemaining()) {
        val r = read(dst)
        if (r < 0) return if (total == 0) -1 else total
        total += r
    }
    return total
}

/** Decrypts the whole file both ways (seekable channel and stream) and checks it against the generator. */
fun VaultCrypto.verifyGenerated(file: File, header: FileHeader, size: Long) {
    verifySeekable(file, header, size)
    verifyStream(file, header, size)
}

fun VaultCrypto.verifySeekable(file: File, header: FileHeader, size: Long) {
    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
        newSeekableDecryptingChannel(fc, header).use { ch ->
            compareSequential(size) { buf -> ch.readFully(buf) }
        }
    }
}

fun VaultCrypto.verifyStream(file: File, header: FileHeader, size: Long) {
    file.inputStream().use { input ->
        newDecryptingStream(input, header).use { s ->
            compareSequential(size) { buf ->
                val r = s.read(buf.array(), buf.position(), buf.remaining())
                if (r > 0) buf.position(buf.position() + r)
                r
            }
        }
    }
}

private fun compareSequential(size: Long, read: (ByteBuffer) -> Int) {
    val got = ByteBuffer.allocate(CHUNK)
    val want = ByteArray(CHUNK)
    var pos = 0L
    while (true) {
        got.clear()
        val r = read(got)
        if (r < 0) break
        Gen.fill(want, r, pos)
        if (!java.util.Arrays.equals(got.array(), 0, r, want, 0, r)) fail("mismatch in chunk at $pos")
        pos += r
    }
    if (pos != size) fail("read $pos bytes, expected $size")
}

/** The block must fail with IOException or GeneralSecurityException (planning pack §6 PR2 tamper tests). */
fun assertRejected(what: String, block: () -> Unit) {
    val e = runCatching(block).exceptionOrNull()
    if (e is AssertionError) throw e // wrong data came back without an error
    if (e !is IOException && e !is GeneralSecurityException) {
        throw AssertionError("$what: expected IOException or GeneralSecurityException, got $e", e)
    }
}
