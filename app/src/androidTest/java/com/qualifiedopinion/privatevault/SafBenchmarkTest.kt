package com.qualifiedopinion.privatevault

import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.toHex
import com.qualifiedopinion.privatevault.vault.FileVaultStorage
import com.qualifiedopinion.privatevault.vault.Grant
import com.qualifiedopinion.privatevault.vault.ItemKind
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.NewItem
import com.qualifiedopinion.privatevault.vault.SafVaultStorage
import com.qualifiedopinion.privatevault.vault.VaultFiles
import com.qualifiedopinion.privatevault.vault.VaultIndex
import com.qualifiedopinion.privatevault.vault.VaultSession
import com.qualifiedopinion.privatevault.vault.VaultStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.util.Random

/**
 * PR3 SAF acceptance benchmark (planning pack §6 PR3), for the target phone only. **Not run in CI.**
 *
 * Compares the SAF tree (ParcelFileDescriptor → FileChannel) with a plain File in this app's
 * app-specific external dir on the same volume:
 * 1. sequential encrypt + write of [ARG_SIZE_MIB] MiB (default 1024) incl. fsync, then decrypt + read;
 *    asserts SAF ≥ 80 % of File throughput for both;
 * 2. 200 random seeks + 64 KiB reads on the SAF file; asserts average < 50 ms;
 * 3. 1,000 items created through [ItemRepository]; asserts a cold listing of `items/` < 2 s, and logs
 *    the full index build (decrypting every `.vmeta`).
 *
 * Page cache: an unrooted app can't drop it, so with a file smaller than RAM part of each read may be
 * served from memory (both sides alike). Pass `-e sizeMiB` above the phone's RAM for a cold read.
 *
 * The tree must already be granted to the app (do the in-app setup on the SD card first). The run
 * works only in a fresh `pv-bench-<random>` sub-folder of that tree, so the vault is not touched, and
 * deletes it at the end. Without `-e treeUri …` the test is skipped. Command: docs/STATUS.md (PR3).
 * Results: `adb logcat -s PV-BENCH`.
 */
@RunWith(AndroidJUnit4::class)
class SafBenchmarkTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as PrivateVaultApp).graph

    @Test
    fun safVersusFileOnTheSameVolume() {
        val treeArg = args.getString(ARG_TREE_URI)
        if (treeArg == null) Log.i(TAG, "skipped: no -e $ARG_TREE_URI argument (see docs/STATUS.md)")
        assumeTrue("SAF benchmark skipped: pass -e $ARG_TREE_URI <persisted tree URI> (docs/STATUS.md)", treeArg != null)
        val tree = Uri.parse(treeArg)
        val sizeMiB = args.getString(ARG_SIZE_MIB)?.toLong() ?: DEFAULT_SIZE_MIB
        val itemCount = args.getString(ARG_ITEMS)?.toInt() ?: DEFAULT_ITEMS
        assertEquals("the app holds no read/write grant for this tree; pick it in the app first",
            Grant.READ_WRITE, graph.location.grantFor(tree))
        val volume = need(graph.location.volumeOf(tree), "not an ExternalStorageProvider tree")
        val sm = context.getSystemService(StorageManager::class.java)
        val baseRoot = need(
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull { dir ->
                val v = sm.getStorageVolume(dir)
                v != null && (if (volume.isPrimary) v.isPrimary else v.uuid.equals(volume.volume, ignoreCase = true))
            },
            "no app-specific external dir on the tree's volume",
        )

        val name = "pv-bench-" + FileHeader.newId().toHex().take(8)
        val io = graph.safIo
        val treeRoot = io.documentUri(tree, DocumentsContract.getTreeDocumentId(tree))
        val safDir = io.createDocument(tree, treeRoot, DocumentsContract.Document.MIME_TYPE_DIR, name)
        val fileDir = File(baseRoot, name).apply { mkdirs() }
        val saf = SafVaultStorage(tree, io, readOnly = { false }, rootDocumentId = safDir.documentId)
        val plain = FileVaultStorage(fileDir)
        Log.i(TAG, "start: $sizeMiB MiB, $itemCount items, volume primary=${volume.isPrimary}, sdk=${android.os.Build.VERSION.SDK_INT}")
        try {
            val crypto = VaultCrypto.fromKeyset(VaultCrypto.newDataKeyset())
            val header = FileHeader(FileType.DATA, FileHeader.newId())
            val bytes = sizeMiB * MIB

            val fileWrite = timedWrite(plain, crypto, header, bytes)
            val safWrite = timedWrite(saf, crypto, header, bytes)
            // Unrooted apps can't drop the page cache. The order keeps it symmetric: each read follows
            // sizeMiB of traffic on the other file. Use -e sizeMiB > RAM to defeat the cache entirely.
            val fileRead = timedRead(plain, crypto, header, bytes)
            val safRead = timedRead(saf, crypto, header, bytes)
            val writeRatio = fileWrite.toDouble() / safWrite
            val readRatio = fileRead.toDouble() / safRead
            Log.i(TAG, "write File ${mibps(bytes, fileWrite)} MiB/s, SAF ${mibps(bytes, safWrite)} MiB/s, ratio %.3f".format(writeRatio))
            Log.i(TAG, "read  File ${mibps(bytes, fileRead)} MiB/s, SAF ${mibps(bytes, safRead)} MiB/s, ratio %.3f".format(readRatio))

            val fileSeek = seeks(plain, crypto, header, bytes)
            val safSeek = seeks(saf, crypto, header, bytes)
            Log.i(TAG, "seek+64KiB x$SEEKS avg File %.1f ms, SAF %.1f ms (SAF max %.1f ms)".format(fileSeek.first, safSeek.first, safSeek.second))
            saf.delete(VaultStorage.ROOT, BIG)
            plain.delete(VaultStorage.ROOT, BIG)

            val fileList = listing(plain, itemCount) { FileVaultStorage(fileDir) }
            val safList = listing(saf, itemCount) {
                SafVaultStorage(tree, io, readOnly = { false }, rootDocumentId = safDir.documentId)
            }
            Log.i(TAG, "list $itemCount items: File ${fileList.first} ms, SAF ${safList.first} ms")
            Log.i(TAG, "index build (decrypt every .vmeta): File ${fileList.second} ms, SAF ${safList.second} ms")

            val pass = writeRatio >= MIN_RATIO && readRatio >= MIN_RATIO && safSeek.first < MAX_SEEK_MS && safList.first < MAX_LIST_MS
            Log.i(TAG, "result: ${if (pass) "PASS" else "FAIL"}")
            assertTrue("SAF write ratio %.3f < $MIN_RATIO".format(writeRatio), writeRatio >= MIN_RATIO)
            assertTrue("SAF read ratio %.3f < $MIN_RATIO".format(readRatio), readRatio >= MIN_RATIO)
            assertTrue("SAF seek avg %.1f ms ≥ $MAX_SEEK_MS ms".format(safSeek.first), safSeek.first < MAX_SEEK_MS)
            assertTrue("SAF listing ${safList.first} ms ≥ $MAX_LIST_MS ms", safList.first < MAX_LIST_MS)
        } finally {
            io.delete(io.documentUri(tree, safDir.documentId)) // ExternalStorageProvider deletes folders recursively
            fileDir.deleteRecursively()
        }
    }

    /** Encrypt + write + fsync; returns ms. The same 1 MiB of random plaintext is fed repeatedly. */
    private fun timedWrite(storage: VaultStorage, crypto: VaultCrypto, header: FileHeader, bytes: Long): Long {
        val chunk = ByteArray(MIB.toInt()).also { Random(1).nextBytes(it) }
        val t0 = System.nanoTime()
        val ch = storage.create(VaultStorage.ROOT, BIG)
        crypto.newEncryptingChannel(SyncOnClose(ch), header).use { enc ->
            var left = bytes
            while (left > 0) {
                val buf = ByteBuffer.wrap(chunk, 0, minOf(left, MIB).toInt())
                left -= buf.remaining()
                while (buf.hasRemaining()) enc.write(buf)
            }
        }
        return (System.nanoTime() - t0) / NANOS_PER_MS
    }

    private fun timedRead(storage: VaultStorage, crypto: VaultCrypto, header: FileHeader, bytes: Long): Long {
        val buf = ByteBuffer.allocate(MIB.toInt())
        var total = 0L
        val t0 = System.nanoTime()
        crypto.newSeekableDecryptingChannel(storage.openRead(VaultStorage.ROOT, BIG), header).use { ch ->
            while (true) {
                buf.clear()
                val n = ch.read(buf)
                if (n < 0) break
                total += n
            }
        }
        val ms = (System.nanoTime() - t0) / NANOS_PER_MS
        assertEquals(bytes, total)
        return ms
    }

    /** [SEEKS] seeded random positions, 64 KiB read at each. Returns (average ms, max ms). */
    private fun seeks(storage: VaultStorage, crypto: VaultCrypto, header: FileHeader, bytes: Long): Pair<Double, Double> {
        val rnd = Random(SEEK_SEED)
        val buf = ByteBuffer.allocate(SEEK_READ)
        var sum = 0.0
        var max = 0.0
        crypto.newSeekableDecryptingChannel(storage.openRead(VaultStorage.ROOT, BIG), header).use { ch ->
            repeat(SEEKS) {
                val pos = (rnd.nextDouble() * (bytes - SEEK_READ)).toLong()
                val t0 = System.nanoTime()
                ch.position(pos)
                buf.clear()
                while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                val ms = (System.nanoTime() - t0) / NANOS_PER_MS_D
                sum += ms
                max = maxOf(max, ms)
                assertEquals(SEEK_READ, buf.position())
            }
        }
        return sum / SEEKS to max
    }

    /** Creates [n] small items, then times a cold listing and a full index build. Returns (list ms, index ms). */
    private fun listing(storage: VaultStorage, n: Int, fresh: () -> VaultStorage): Pair<Long, Long> {
        val session = VaultSession(VaultIndex())
        session.unlock(VaultCrypto.newDataKeyset(), FileHeader.newId())
        val files = VaultFiles(storage)
        for (dir in VaultFiles.LAYOUT_DIRS) storage.ensureDir(dir)
        val repo = ItemRepository(files, session, VaultIndex())
        val t0 = System.nanoTime()
        repeat(n) { i ->
            repo.create(NewItem(ItemKind.PHOTO, "image/jpeg", "IMG_$i.jpg"), ByteArrayInputStream(ByteArray(ITEM_BYTES)), ByteArray(THUMB_BYTES))
        }
        Log.i(TAG, "created $n items in ${(System.nanoTime() - t0) / NANOS_PER_MS} ms (${storage.javaClass.simpleName})")
        val cold = fresh()
        val t1 = System.nanoTime()
        val names = cold.list(VaultFiles.ITEMS)
        val listMs = (System.nanoTime() - t1) / NANOS_PER_MS
        assertEquals(n * FILES_PER_ITEM, names.size)
        val coldRepo = ItemRepository(VaultFiles(fresh()), session, VaultIndex())
        val t2 = System.nanoTime()
        val loaded = coldRepo.loadAll()
        val indexMs = (System.nanoTime() - t2) / NANOS_PER_MS
        assertEquals(n, loaded.items.size)
        session.lock()
        return listMs to indexMs
    }

    private fun mibps(bytes: Long, ms: Long) = "%.1f".format(bytes / MIB.toDouble() / (ms / 1000.0))

    private class SyncOnClose(private val ch: FileChannel) : WritableByteChannel {
        override fun write(src: ByteBuffer) = ch.write(src)
        override fun isOpen() = ch.isOpen
        override fun close() = ch.use { it.force(true) }
    }

    private fun <T : Any> need(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }

    private companion object {
        const val TAG = "PV-BENCH"
        const val ARG_TREE_URI = "treeUri"
        const val ARG_SIZE_MIB = "sizeMiB"
        const val ARG_ITEMS = "items"
        const val DEFAULT_SIZE_MIB = 1024L
        const val DEFAULT_ITEMS = 1000
        const val MIB = 1024L * 1024
        const val BIG = "big.vdat"
        const val SEEKS = 200
        const val SEEK_READ = 64 * 1024
        const val SEEK_SEED = 42L
        const val ITEM_BYTES = 2048
        const val THUMB_BYTES = 512
        const val FILES_PER_ITEM = 3
        const val MIN_RATIO = 0.80
        const val MAX_SEEK_MS = 50.0
        const val MAX_LIST_MS = 2000L
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_MS_D = 1_000_000.0
    }
}
