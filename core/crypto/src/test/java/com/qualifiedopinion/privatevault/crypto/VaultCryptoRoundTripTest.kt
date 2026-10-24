package com.qualifiedopinion.privatevault.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.VaultFormatException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException

class VaultCryptoRoundTripTest {
    private val crypto = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
    private val header = FileHeader(FileType.DATA, FileHeader.newId())
    private val file = TestFiles.newFile("roundtrip")

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun segmentConstantsMatchTink() {
        assertEquals(1_048_560L, SEG)
        assertEquals(1_048_520L, FIRST_SEG)
    }

    @Test
    fun roundTripsAtSegmentBoundaries() {
        val sizes = listOf(
            0L, 1L, 2L,
            FIRST_SEG - 1, FIRST_SEG, FIRST_SEG + 1,
            SEG - 1, SEG, SEG + 1,
            FIRST_SEG + SEG - 1, FIRST_SEG + SEG, FIRST_SEG + SEG + 1,
            2 * SEG - 1, 2 * SEG, 2 * SEG + 1,
        )
        for (size in sizes) {
            crypto.encryptGenerated(file, header, size)
            assertEquals("file size for $size", VaultCrypto.fileSizeFor(size), file.length())
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
                val ch = crypto.newSeekableDecryptingChannel(fc, header)
                assertEquals("size() for $size", size, ch.size())
            }
            crypto.verifyGenerated(file, header, size)
        }
    }

    @Test
    fun emptyFileReadsEofImmediately() {
        crypto.encryptGenerated(file, header, 0)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            crypto.newSeekableDecryptingChannel(fc, header).use { ch ->
                assertEquals(0L, ch.size())
                assertEquals(-1, ch.read(ByteBuffer.allocate(16)))
            }
        }
    }

    @Test
    fun eofReturnsMinusOneFromAnyPosition() {
        for (size in listOf(1L, FIRST_SEG, FIRST_SEG + 1, SEG + 7)) {
            crypto.encryptGenerated(file, header, size)
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
                crypto.newSeekableDecryptingChannel(fc, header).use { ch ->
                    ch.position(size)
                    assertEquals(-1, ch.read(ByteBuffer.allocate(8)))
                    ch.position(0)
                    assertEquals(minOf(size, 8L).toInt(), ch.readFully(ByteBuffer.allocate(8)))
                    ch.position(size + 100)
                    assertEquals(-1, ch.read(ByteBuffer.allocate(8)))
                }
            }
        }
    }

    @Test
    fun fileTruncatedToAnEmptyCiphertextIsRejected() {
        // Keep our header + Tink header + 16 bytes: size() computes as 0, so EOF must authenticate.
        crypto.encryptGenerated(file, header, 100)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(VaultCrypto.fileSizeFor(0)) }
        assertRejected("seekable") { crypto.verifySeekable(file, header, 0) }
        assertRejected("stream") { crypto.verifyStream(file, header, 0) }
    }

    @Test
    fun chainedPositionKeepsTheEofCheck() {
        crypto.encryptGenerated(file, header, 100)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(VaultCrypto.fileSizeFor(0)) }
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            val ch = crypto.newSeekableDecryptingChannel(fc, header)
            assertSame(ch, ch.position(0))
            assertRejected("chained position") { ch.position(0).read(ByteBuffer.allocate(16)) }
        }
    }

    @Test
    fun fileStartsWithTheHeaderAndTinkHeaderLength() {
        crypto.encryptGenerated(file, header, 10)
        val bytes = file.readBytes()
        assertEquals(header, FileHeader.decode(bytes))
        assertEquals(VaultCrypto.TINK_HEADER_SIZE, bytes[FileHeader.SIZE].toInt())
    }

    @Test
    fun wrongExpectedHeaderIsRejectedBeforeDecrypting() {
        crypto.encryptGenerated(file, header, 100)
        val otherId = FileHeader(FileType.DATA, FileHeader.newId())
        val otherType = FileHeader(FileType.THUMB, header.id)
        for (expected in listOf(otherId, otherType)) {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
                assertThrows(VaultFormatException::class.java) { crypto.newSeekableDecryptingChannel(fc, expected) }
            }
            file.inputStream().use { input ->
                assertThrows(VaultFormatException::class.java) { crypto.newDecryptingStream(input, expected) }
            }
        }
    }

    @Test
    fun anotherDataKeyCannotDecrypt() {
        crypto.encryptGenerated(file, header, 5000)
        val other = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
        assertRejected("other key, seekable") { other.verifyGenerated(file, header, 5000) }
    }

    @Test
    fun keysetMustBeOneEnabledAes256GcmHkdf1MbKey() {
        val wrongParams = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES128_GCM_HKDF_1MB)
        assertThrows(GeneralSecurityException::class.java) { VaultCrypto.fromKeyset(wrongParams) }

        val twoKeys = KeysetHandle.newBuilder(VaultCrypto.newDataKeyset())
            .addEntry(
                KeysetHandle.generateEntryFromParameters(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
                    .withRandomId(),
            )
            .build()
        assertThrows(GeneralSecurityException::class.java) { VaultCrypto.fromKeyset(twoKeys) }
    }
}
