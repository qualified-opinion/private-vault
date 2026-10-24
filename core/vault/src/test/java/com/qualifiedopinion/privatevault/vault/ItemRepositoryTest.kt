package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.toHex
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.ITEMS
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.STAGING
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random

class ItemRepositoryTest {
    @get:Rule
    val tmp = vaultTempFolder()

    private val device = SoftwareKeyWrapper()
    private val keys = VaultKeys(FakeKdf()) { device }
    private val index = VaultIndex()
    private val session = VaultSession(index)
    private lateinit var root: File
    private lateinit var files: VaultFiles
    private lateinit var vaultId: ByteArray
    private var now = 1_000L
    private lateinit var repo: ItemRepository

    @Before
    fun setUp() {
        root = tmp.newFolder()
        files = VaultFiles(FileVaultStorage(root))
        vaultId = keys.create(files, pin("123456"), KdfParams.FLOOR, session)
        repo = ItemRepository(files, session, index) { now++ }
    }

    private fun bytes(n: Int, seed: Long = n.toLong()) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun add(data: ByteArray, thumb: ByteArray = bytes(300), name: String = "IMG.jpg") =
        repo.create(NewItem(ItemKind.PHOTO, "image/jpeg", name, w = 4, h = 3), ByteArrayInputStream(data), thumb)

    private fun itemFiles(id: String) =
        listOf(VaultFiles.dataName(id), VaultFiles.thumbName(id), VaultFiles.metaName(id))

    @Test
    fun createWritesThreeEncryptedFilesAndIndexesTheItem() {
        val data = bytes(3 * 1024 * 1024 + 17)
        val thumb = bytes(500)
        val item = add(data, thumb)
        assertEquals(itemFiles(item.id).sorted(), root.child(ITEMS).list()!!.sorted())
        assertEquals(0, root.child(STAGING).list()!!.size)
        assertEquals(data.size.toLong(), item.size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).toHex(), item.sha256)
        assertEquals(listOf(item), index.items.value)

        // Every file is header + ciphertext bound to this item; no plaintext on disk.
        val idBytes = ItemRepository.idFromHex(item.id)
        for ((name, type) in itemFiles(item.id).zip(listOf(FileType.DATA, FileType.THUMB, FileType.META))) {
            val raw = root.child(ITEMS, name).readBytes()
            assertEquals(FileHeader(type, idBytes), FileHeader.decode(raw))
            assertFalse(name, containsSlice(raw, "IMG.jpg".toByteArray()))
        }
        val dataFile = root.child(ITEMS, VaultFiles.dataName(item.id)).readBytes()
        assertFalse(containsSlice(dataFile, data.copyOfRange(1000, 1064)))

        assertArrayEquals(thumb, repo.readThumbnail(item.id))
        repo.openData(item.id).use { ch ->
            assertEquals(data.size.toLong(), ch.size())
            val out = ByteBuffer.allocate(data.size)
            while (out.hasRemaining()) if (ch.read(out) < 0) break
            assertArrayEquals(data, out.array())
            ch.position(2_000_000)
            val part = ByteBuffer.allocate(64)
            ch.read(part)
            assertArrayEquals(data.copyOfRange(2_000_000, 2_000_064), part.array())
        }
    }

    @Test
    fun loadAllRebuildsIndexAfterUnlock() {
        val a = add(bytes(10, 1))
        val b = add(bytes(20, 2))
        val c = add(bytes(0, 3))
        session.lock()
        assertEquals(0, index.size)
        keys.unlock(files, pin("123456"), vaultId, session)
        val result = repo.loadAll()
        assertEquals(0, result.skipped)
        assertEquals(listOf(c.id, b.id, a.id), result.items.map { it.id }) // newest first
        assertEquals(listOf(c, b, a), index.items.value)
        assertEquals(0L, index[c.id]!!.size)
    }

    @Test
    fun loadAllSkipsOrphansCorruptAndSwappedMetadata() {
        val good = add(bytes(10, 1))
        val orphan = add(bytes(10, 2))
        val corrupt = add(bytes(10, 3))
        val victim = add(bytes(10, 4))
        root.child(ITEMS, VaultFiles.dataName(orphan.id)).delete()
        val c = root.child(ITEMS, VaultFiles.metaName(corrupt.id))
        c.writeBytes(c.readBytes().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() })
        // Copy good's .vmeta over victim's: header/AAD names the other item, so it must not load.
        val goodMeta = root.child(ITEMS, VaultFiles.metaName(good.id))
        goodMeta.copyTo(root.child(ITEMS, VaultFiles.metaName(victim.id)), overwrite = true)
        root.child(ITEMS, "notes.vmeta").writeBytes(ByteArray(3))

        val result = repo.loadAll()
        assertEquals(listOf(good.id), result.items.map { it.id })
        assertEquals(4, result.skipped)
    }

    @Test
    fun deleteRemovesAllThreeFilesAndUpdatesIndex() {
        val keep = add(bytes(10, 1))
        val gone = add(bytes(10, 2))
        repo.delete(gone.id)
        assertEquals(itemFiles(keep.id).sorted(), root.child(ITEMS).list()!!.sorted())
        assertEquals(listOf(keep), index.items.value)
        assertEquals(listOf(keep.id), repo.loadAll().items.map { it.id })
        repo.delete(gone.id) // idempotent
    }

    @Test
    fun readOnlyFolderAllowsViewButNotImportOrDelete() {
        val item = add(bytes(1000))
        session.lock()
        val roFiles = VaultFiles(FileVaultStorage(root, readOnly = true))
        keys.unlock(roFiles, pin("123456"), vaultId, session)
        val ro = ItemRepository(roFiles, session, index)
        assertEquals(listOf(item.id), ro.loadAll().items.map { it.id })
        ro.openData(item.id).use { assertEquals(1000L, it.size()) }
        val before = root.child(ITEMS).list()!!.sorted()
        assertThrows(VaultReadOnlyException::class.java) { ro.delete(item.id) }
        assertThrows(VaultReadOnlyException::class.java) {
            ro.create(NewItem(ItemKind.VIDEO, "video/mp4", "v.mp4"), ByteArrayInputStream(bytes(5)), ByteArray(0))
        }
        assertEquals(before, root.child(ITEMS).list()!!.sorted())
        assertEquals(listOf(item.id), index.items.value.map { it.id })
    }

    @Test
    fun failedCreateLeavesNothingBehind() {
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ > 3) throw IOException("source vanished")
                return len
            }
        }
        assertThrows(IOException::class.java) {
            repo.create(NewItem(ItemKind.VIDEO, "video/mp4", "v.mp4"), failing, ByteArray(10))
        }
        assertEquals(0, root.child(ITEMS).list()!!.size)
        assertEquals(0, root.child(STAGING).list()!!.size)
        assertEquals(0, index.size)
    }

    @Test
    fun crashMidCreateIsCleanedUpOrInvisible() {
        var step = 0
        while (true) {
            val crashing = CrashingStorage(FileVaultStorage(root), failAt = step)
            val r = ItemRepository(VaultFiles(crashing), session, VaultIndex())
            val done = try {
                r.create(NewItem(ItemKind.PHOTO, "image/png", "p.png"), ByteArrayInputStream(bytes(100)), ByteArray(5))
                true
            } catch (@Suppress("SwallowedException") e: SimulatedCrash) {
                false
            }
            if (done) break
            // Partial items never show up, and startup cleanup leaves no .part files.
            files.cleanStaging()
            assertEquals("step $step", 0, repo.loadAll().items.size)
            assertTrue(root.child(STAGING).list()!!.isEmpty())
            assertTrue(root.child(ITEMS).list()!!.none { it.endsWith(".part") })
            step++
        }
        assertTrue(step >= 4)
    }

    @Test
    fun lockedRepositoryRefusesAndLockClosesOpenChannels() {
        val item = add(bytes(5000))
        val ch = repo.openData(item.id)
        assertTrue(ch.isOpen)
        session.lock()
        assertFalse(ch.isOpen)
        assertThrows(VaultLockedException::class.java) { repo.openData(item.id) }
        assertThrows(VaultLockedException::class.java) { repo.loadAll() }
        assertThrows(VaultLockedException::class.java) { add(bytes(5)) }
    }

    @Test
    fun chainedPositionReturnsTheTrackedChannel() {
        val item = add(bytes(5000))
        val ch = repo.openData(item.id)
        assertSame(ch, ch.position(10))
        ch.position(0).close() // must untrack, not just close the inner channel
        session.lock()
    }

    @Test
    fun closingAChannelUntracksIt() {
        val item = add(bytes(50))
        repeat(3) { repo.openData(item.id).close() }
        session.lock() // nothing left to close; must not throw
    }

    @Test
    fun streamsLargeItemsInSmallHeap() {
        // The test JVM has a 64 MiB heap: a 96 MiB item can only pass if nothing buffers the whole file.
        val size = 96L * 1024 * 1024
        val source = object : InputStream() {
            var left = size
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left == 0L) return -1
                val n = minOf(len.toLong(), left).toInt()
                for (i in off until off + n) b[i] = (i * 31).toByte()
                left -= n
                return n
            }
        }
        val item = repo.create(NewItem(ItemKind.VIDEO, "video/mp4", "big.mp4"), source, ByteArray(1))
        assertEquals(size, item.size)
        repo.openData(item.id).use { assertEquals(size, it.size()) }
        repo.delete(item.id)
    }

    private fun containsSlice(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
