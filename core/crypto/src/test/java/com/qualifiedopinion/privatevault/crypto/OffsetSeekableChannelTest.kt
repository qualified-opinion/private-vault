package com.qualifiedopinion.privatevault.crypto

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class OffsetSeekableChannelTest {
    private val file = TestFiles.newFile("offset").also { f -> f.writeBytes(ByteArray(100) { it.toByte() }) }

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun shiftsPositionAndSize() {
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { fc ->
            val ch = OffsetSeekableChannel(fc, 32)
            assertEquals(0L, ch.position())
            assertEquals(32L, fc.position())
            assertEquals(68L, ch.size())

            val buf = ByteBuffer.allocate(4)
            ch.read(buf)
            assertArrayEquals(byteArrayOf(32, 33, 34, 35), buf.array())
            assertEquals(4L, ch.position())

            ch.position(60)
            buf.clear()
            ch.read(buf)
            assertArrayEquals(byteArrayOf(92, 93, 94, 95), buf.array())

            ch.position(68)
            assertEquals(-1, ch.read(ByteBuffer.allocate(1)))

            ch.truncate(10)
            assertEquals(10L, ch.size())
            assertEquals(42L, fc.size())

            ch.close()
            assertFalse(fc.isOpen)
        }
    }

    @Test
    fun keepsBasePositionIfAlreadyPastOffset() {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            fc.position(40)
            assertEquals(8L, OffsetSeekableChannel(fc, 32).position())
        }
    }

    @Test
    fun sizeIsNeverNegative() {
        file.writeBytes(ByteArray(10))
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            assertEquals(0L, OffsetSeekableChannel(fc, 32).size())
        }
    }
}
