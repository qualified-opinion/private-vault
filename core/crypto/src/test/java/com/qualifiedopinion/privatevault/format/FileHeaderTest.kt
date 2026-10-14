package com.qualifiedopinion.privatevault.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class FileHeaderTest {
    private val id = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun encodesTheDocumentedLayout() {
        val bytes = FileHeader(FileType.THUMB, id).encode()
        assertEquals(32, bytes.size)
        assertArrayEquals(byteArrayOf(0x50, 0x56, 0x4C, 0x54), bytes.copyOfRange(0, 4)) // "PVLT"
        assertEquals(0x03, bytes[4].toInt())
        assertEquals(0x01, bytes[5].toInt())
        assertArrayEquals(ByteArray(2), bytes.copyOfRange(6, 8))
        assertArrayEquals(id, bytes.copyOfRange(8, 24))
        assertArrayEquals(ByteArray(8), bytes.copyOfRange(24, 32))
    }

    @Test
    fun roundTripsEveryType() {
        for (type in FileType.entries) {
            val h = FileHeader(type, id)
            assertEquals(h, FileHeader.decode(h.encode()))
            assertEquals(h, FileHeader.decode(byteArrayOf(9, 9) + h.encode(), offset = 2))
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), FileType.entries.map { it.code })
    }

    @Test
    fun rejectsUnknownMagic() {
        assertRejected(0, 0x51)
        assertRejected(3, 0x00)
    }

    @Test
    fun rejectsUnknownType() {
        for (t in listOf(0x00, 0x07, 0x7F, 0xFF)) assertRejected(4, t)
    }

    @Test
    fun rejectsUnsupportedMajorVersion() {
        for (v in listOf(0x00, 0x02, 0xFF)) assertRejected(5, v)
    }

    @Test
    fun rejectsNonZeroFlagsAndReserved() {
        assertRejected(6, 0x01)
        assertRejected(7, 0x80)
        assertRejected(24, 0x01)
        assertRejected(31, 0x01)
    }

    @Test
    fun rejectsTruncatedHeader() {
        val bytes = FileHeader(FileType.DATA, id).encode()
        assertThrows(VaultFormatException::class.java) { FileHeader.decode(bytes.copyOf(31)) }
        assertThrows(VaultFormatException::class.java) { FileHeader.readBytes(ByteArrayInputStream(bytes.copyOf(20))) }
    }

    @Test
    fun idsAreRandom16Bytes() {
        val a = FileHeader.newId()
        val b = FileHeader.newId()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
        assertThrows(IllegalArgumentException::class.java) { FileHeader(FileType.DATA, ByteArray(15)) }
    }

    @Test
    fun idIsDefensivelyCopied() {
        val src = id.copyOf()
        val h = FileHeader(FileType.DATA, src)
        src[0] = 99
        h.id[1] = 99
        assertArrayEquals(id, h.id)
    }

    @Test
    fun hexIsLowercase32Chars() {
        assertEquals("0102030405060708090a0b0c0d0e0f10", FileHeader(FileType.DATA, id).idHex)
        assertEquals("00ff7f80", byteArrayOf(0, -1, 0x7F, -128).toHex())
    }

    private fun assertRejected(offset: Int, value: Int) {
        val bytes = FileHeader(FileType.DATA, id).encode()
        bytes[offset] = value.toByte()
        assertThrows("offset $offset = $value", VaultFormatException::class.java) { FileHeader.decode(bytes) }
    }
}
