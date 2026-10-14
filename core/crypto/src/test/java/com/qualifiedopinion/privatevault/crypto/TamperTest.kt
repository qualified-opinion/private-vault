package com.qualifiedopinion.privatevault.crypto

import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Every tamper case must make decryption throw IOException or GeneralSecurityException,
 * through both the seekable channel and the stream (planning pack §6 PR2).
 */
class TamperTest {
    private val crypto = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
    private val header = FileHeader(FileType.DATA, FileHeader.newId())
    private val file = TestFiles.newFile("tamper")

    // Four Tink segments: 0 (first), 1 and 2 (full), 3 (last, partial).
    private val size = FIRST_SEG + 2 * SEG + 1000

    @Before
    fun encrypt() {
        crypto.encryptGenerated(file, header, size)
        crypto.verifyGenerated(file, header, size) // sanity: untampered file is fine
    }

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun flippedHeaderBytesAreRejected() {
        // Our header: magic, type, major, flags, id, reserved. Tink header: length byte, HKDF salt, nonce prefix.
        val offsets = listOf(0, 4, 5, 6, 8, 23, 24, 31, 32, 33, 64, 65, 71)
        for (offset in offsets) {
            withFlippedByte(offset.toLong()) { decryptAll(header, "header byte $offset") }
        }
    }

    @Test
    fun flippedMidFileCiphertextByteIsRejected() {
        withFlippedByte(file.length() / 2) { decryptAll(header, "mid-file byte") }
    }

    @Test
    fun flippedLastTagByteIsRejected() {
        withFlippedByte(file.length() - 1) { decryptAll(header, "last tag byte") }
    }

    @Test
    fun truncatedFileIsRejected() {
        val full = file.length()
        val lastSegmentStart = FileHeader.SIZE + 3L * VaultCrypto.CIPHERTEXT_SEGMENT_SIZE
        // By one byte, inside the last segment, and exactly at a segment boundary (drops the final segment).
        for (newLength in listOf(full - 1, full - 500, lastSegmentStart)) {
            crypto.encryptGenerated(file, header, size)
            RandomAccessFile(file, "rw").use { it.setLength(newLength) }
            decryptAll(header, "truncated to $newLength")
        }
    }

    @Test
    fun swappedSegmentsAreRejected() {
        val seg = VaultCrypto.CIPHERTEXT_SEGMENT_SIZE
        val s1 = FileHeader.SIZE.toLong() + seg
        val s2 = s1 + seg
        RandomAccessFile(file, "rw").use { raf ->
            val a = ByteArray(seg)
            val b = ByteArray(seg)
            raf.seek(s1)
            raf.readFully(a)
            raf.seek(s2)
            raf.readFully(b)
            raf.seek(s1)
            raf.write(b)
            raf.seek(s2)
            raf.write(a)
        }
        decryptAll(header, "swapped segments")
    }

    @Test
    fun itemIdSwappedInAadIsRejected() {
        // An attacker rewrites the header to claim another item id; the reader expects that id.
        val forged = FileHeader(FileType.DATA, FileHeader.newId())
        rewriteHeader(forged)
        decryptAll(forged, "forged id, forged expectation")
        decryptAll(header, "forged id, original expectation")
    }

    @Test
    fun changedTypeByteIsRejected() {
        val forged = FileHeader(FileType.THUMB, header.id)
        rewriteHeader(forged)
        decryptAll(forged, "type byte, forged expectation")
        decryptAll(header, "type byte, original expectation")
    }

    /** Both read paths must reject on their own. A mismatch without an exception fails the test (AssertionError). */
    private fun decryptAll(expected: FileHeader, case: String) {
        assertRejected("$case (seekable)") { crypto.verifySeekable(file, expected, size) }
        assertRejected("$case (stream)") { crypto.verifyStream(file, expected, size) }
    }

    private fun rewriteHeader(h: FileHeader) {
        RandomAccessFile(file, "rw").use { it.write(h.encode()) }
    }

    private fun withFlippedByte(offset: Long, block: () -> Unit) {
        flip(file, offset)
        try {
            block()
        } finally {
            flip(file, offset)
        }
    }

    private fun flip(f: File, offset: Long) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(offset)
            val b = raf.read()
            raf.seek(offset)
            raf.write(b xor 0x01)
        }
    }
}
