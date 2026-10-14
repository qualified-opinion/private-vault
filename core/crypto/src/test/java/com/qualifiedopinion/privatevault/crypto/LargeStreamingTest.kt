package com.qualifiedopinion.privatevault.crypto

import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.Random

/**
 * Planning pack §6 PR2 large streaming test. Size comes from Gradle (`pv.largeTestBytes`):
 * 256 MiB by default, 3 GiB with `-PlargeTests`. The test JVM runs with -Xmx64m.
 */
class LargeStreamingTest {
    private val size = System.getProperty("pv.largeTestBytes")?.toLong() ?: DEFAULT_SIZE
    private val file = TestFiles.newFile("large")

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun streamsAndSeeksWithBoundedHeap() {
        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        @Suppress("ExplicitGarbageCollectionCall") // start the peak measurement from a clean heap
        System.gc()
        heapPools.forEach { it.resetPeakUsage() }

        val crypto = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
        val header = FileHeader(FileType.DATA, FileHeader.newId())

        val t0 = System.nanoTime()
        crypto.encryptGenerated(file, header, size)
        val t1 = System.nanoTime()
        assertEquals(VaultCrypto.fileSizeFor(size), file.length())

        crypto.verifyStream(file, header, size)
        val t2 = System.nanoTime()

        val positions = seekPositions()
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            crypto.newSeekableDecryptingChannel(fc, header).use { ch ->
                assertEquals(size, ch.size())
                checkReads(ch, positions)
                ch.position(size)
                assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
                ch.position(size + 1)
                assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
            }
        }
        val t3 = System.nanoTime()

        // Sum of per-pool peaks: an upper bound on the true peak of used heap.
        val peakBytes = heapPools.sumOf { it.peakUsage.used }
        val maxHeap = Runtime.getRuntime().maxMemory()
        println(
            (
                "LARGE_TEST bytes=$size maxHeapMiB=${maxHeap / MIB} peakHeapMiB=%.1f " +
                    "encryptMs=%d decryptStreamMs=%d seekMs=%d seeks=%d"
                ).format(peakBytes.toDouble() / MIB, ms(t0, t1), ms(t1, t2), ms(t2, t3), positions.size),
        )
        assertTrue("test JVM must run with -Xmx64m (max heap ${maxHeap / MIB} MiB)", maxHeap <= 64 * MIB)
        assertTrue("peak heap ${peakBytes / MIB} MiB must be < 48 MiB", peakBytes < 48 * MIB)
    }

    /** 2,000 seeded random positions plus every boundary: k·segPlain ± 1, real segment edges, last byte, EOF. */
    private fun seekPositions(): List<Long> {
        val rnd = Random(SEED)
        val out = LinkedHashSet<Long>()
        repeat(RANDOM_POSITIONS) { out += (rnd.nextDouble() * size).toLong() }
        var k = 0L
        while (k * SEG <= size + 1) {
            out += k * SEG - 1
            out += k * SEG + 1
            k++
        }
        var edge = FIRST_SEG
        while (edge <= size + 1) {
            out += edge - 1
            out += edge
            out += edge + 1
            edge += SEG
        }
        out += 0L
        out += size - 1
        out += size
        return out.filter { it in 0..size }
    }

    private fun checkReads(ch: SeekableByteChannel, positions: List<Long>) {
        val rnd = Random(SEED + 1)
        val got = ByteBuffer.allocate(MAX_READ)
        val want = ByteArray(MAX_READ)
        for (pos in positions) {
            val len = 1 + rnd.nextInt(MAX_READ)
            ch.position(pos)
            assertEquals(pos, ch.position())
            got.clear().limit(len)
            val n = ch.readFully(got)
            val expected = minOf(len.toLong(), size - pos).toInt()
            if (expected == 0) {
                assertEquals("read at EOF ($pos)", -1, n)
                continue
            }
            assertEquals("bytes read at $pos", expected, n)
            Gen.fill(want, n, pos)
            if (!java.util.Arrays.equals(got.array(), 0, n, want, 0, n)) fail("data mismatch reading $len at $pos")
        }
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1_000_000

    private companion object {
        const val MIB = 1024L * 1024
        const val DEFAULT_SIZE = 256 * MIB
        const val RANDOM_POSITIONS = 2000
        const val MAX_READ = 70_000
        const val SEED = 0x5EEDL
    }
}
