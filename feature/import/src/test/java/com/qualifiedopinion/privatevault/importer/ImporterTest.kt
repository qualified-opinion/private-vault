package com.qualifiedopinion.privatevault.importer

import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.vault.FileVaultStorage
import com.qualifiedopinion.privatevault.vault.ItemKind
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.VaultFiles
import com.qualifiedopinion.privatevault.vault.VaultIndex
import com.qualifiedopinion.privatevault.vault.VaultSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Random

class ImporterTest {
    @get:Rule
    val tmp = TemporaryFolder(File(System.getProperty("pv.tmpDir") ?: System.getProperty("java.io.tmpdir")))

    private lateinit var root: File
    private val index = VaultIndex()
    private val session = VaultSession(index)
    private lateinit var repo: ItemRepository

    @Before
    fun setUp() {
        root = tmp.newFolder()
        val storage = FileVaultStorage(root)
        VaultFiles.LAYOUT_DIRS.forEach(storage::ensureDir)
        session.unlock(VaultCrypto.newDataKeyset(), FileHeader.newId())
        repo = ItemRepository(VaultFiles(storage), session, index)
    }

    private fun bytes(n: Int, seed: Long = n.toLong()) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private class FakeSource(
        val info: SourceInfo,
        val data: ByteArray = ByteArray(0),
        val failOpen: Boolean = false,
        val details: MediaDetails = MediaDetails(w = 4, h = 3, thumbnail = byteArrayOf(1, 2, 3)),
    ) : ImportSource {
        override fun describe() = info

        override fun open(): InputStream = if (failOpen) throw IOException("gone") else ByteArrayInputStream(data)

        override fun probe(info: SourceInfo) = details
    }

    private fun photo(data: ByteArray, name: String = "IMG.jpg") =
        FakeSource(SourceInfo(name, "image/jpeg", data.size.toLong()), data)

    private fun importer(free: Long? = null, active: () -> Boolean = { true }) = Importer(repo, { free }, active)

    private fun itemFiles() = File(root, VaultFiles.ITEMS).list()!!.sorted()

    private fun stagingFiles() = File(root, VaultFiles.STAGING).list()!!.toList()

    @Test
    fun importsPhotosAndVideosAndSkipsOtherFiles() {
        val photoData = bytes(5000)
        val video = FakeSource(
            SourceInfo("clip.mp4", "video/mp4", 3000),
            bytes(3000),
            details = MediaDetails(durMs = 1234),
        )
        val pdf = FakeSource(SourceInfo("doc.pdf", "application/pdf", 10), bytes(10))
        val progress = mutableListOf<ImportProgress>()

        val result = importer().importAll(listOf(photo(photoData), video, pdf)) { progress += it }

        assertEquals(ImportResult(total = 3, imported = 2, unsupported = 1), result)
        assertEquals(listOf(1, 2, 3), progress.map { it.done })
        assertEquals(2, index.size)
        val savedPhoto = index.items.value.single { it.kind == ItemKind.PHOTO }
        assertEquals("IMG.jpg", savedPhoto.name)
        assertEquals(4, savedPhoto.w)
        assertEquals(1234L, index.items.value.single { it.kind == ItemKind.VIDEO }.durMs)
        repo.openData(savedPhoto.id).use { ch ->
            val buf = ByteBuffer.allocate(photoData.size)
            while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
            assertArrayEquals(photoData, buf.array())
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.readThumbnail(savedPhoto.id))
        assertEquals(6, itemFiles().size) // 2 items x (.vdat, .vthm, .vmeta)
    }

    @Test
    fun itemThatDoesNotFitIsSkippedBeforeWriting() {
        val big = photo(bytes(1000))
        val result = importer(free = ImportRules.SPACE_MARGIN_BYTES).importAll(listOf(big))
        assertEquals(ImportResult(total = 1, noSpace = 1), result)
        assertTrue(itemFiles().isEmpty())
    }

    @Test
    fun unreadableItemIsCountedAndTheRestContinue() {
        val broken = FakeSource(SourceInfo("x.jpg", "image/jpeg", 10), failOpen = true)
        val result = importer().importAll(listOf(broken, photo(bytes(100))))
        assertEquals(ImportResult(total = 2, imported = 1, failed = 1), result)
        assertEquals(3, itemFiles().size)
    }

    @Test
    fun lockingStopsBeforeTheNextItem() {
        var active = true
        val sources = listOf(photo(bytes(10)), photo(bytes(20)))
        val result = importer(active = { active }).importAll(sources) { active = false }
        assertEquals(ImportResult(total = 2, imported = 1, stoppedBy = StopReason.LOCKED), result)
        assertEquals(1, index.size)
    }

    @Test
    fun lockingMidItemRemovesItsPartialFiles() {
        var reads = 0
        // Active for the pre-item check and the first read only: the copy fails on its second read.
        val result = importer(active = { reads++ < 2 }).importAll(listOf(photo(bytes(2_000_000))))
        assertEquals(ImportResult(total = 1, stoppedBy = StopReason.LOCKED), result)
        assertTrue(itemFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
        assertEquals(0, index.size)
    }

    @Test
    fun lockedSessionStopsTheRun() {
        session.lock()
        val result = importer().importAll(listOf(photo(bytes(10))))
        assertEquals(StopReason.LOCKED, result.stoppedBy)
    }

    @Test
    fun readOnlyFolderStopsTheRun() {
        val ro = ItemRepository(VaultFiles(FileVaultStorage(root, readOnly = true)), session, index)
        val result = Importer(ro, { null }, { true }).importAll(listOf(photo(bytes(10))))
        assertEquals(ImportResult(total = 1, stoppedBy = StopReason.READ_ONLY), result)
    }

    @Test
    fun rules() {
        assertEquals(ItemKind.PHOTO, ImportRules.kindOf("image/heic"))
        assertEquals(ItemKind.VIDEO, ImportRules.kindOf("video/mp4"))
        assertNull(ImportRules.kindOf("application/pdf"))
        val need = VaultCrypto.fileSizeFor(1000) + ImportRules.SPACE_MARGIN_BYTES
        assertTrue(ImportRules.fits(1000, need))
        assertTrue(!ImportRules.fits(1000, need - 1))
        assertTrue(ImportRules.fits(null, 0)) // unknown size: try, and let the write fail if it must
        assertTrue(ImportRules.fits(1000, null))
    }

    @Test
    fun dates() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals(1_700_000_000_000L, MediaDates.exif("2023:11:14 22:13:20", utc))
        assertEquals(1_700_000_000_000L, MediaDates.video("20231114T221320.000Z"))
        assertNull(MediaDates.video("19040101T000000.000Z"))
        assertNull(MediaDates.exif("not a date", utc))
    }
}
