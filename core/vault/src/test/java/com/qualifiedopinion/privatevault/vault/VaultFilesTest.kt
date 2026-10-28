package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.VaultFormatException
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.ITEMS
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.STAGING
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META_BAK
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META_TMP
import com.qualifiedopinion.privatevault.vault.VaultStorage.Companion.ROOT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel

class VaultFilesTest {
    @get:Rule
    val tmp = vaultTempFolder()

    private fun newFiles(root: File = tmp.newFolder(), readOnly: Boolean = false) =
        VaultFiles(FileVaultStorage(root, readOnly))

    @Test
    fun createVaultWritesLayoutAndMetaWithoutBackupOrTmp() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val meta = fakeVaultMeta()
        files.createVault(meta)
        assertArrayEquals(meta, root.child(VAULT_META).readBytes())
        assertFalse(root.child(VAULT_META_BAK).exists())
        assertFalse(root.child(VAULT_META_TMP).exists())
        for (d in VaultFiles.LAYOUT_DIRS) assertTrue(d, root.child(d).isDirectory)
        assertFalse(files.readVaultMeta().fromBackup)
    }

    @Test
    fun atomicReplaceKeepsPreviousCopyAsBackup() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val id = FileHeader.newId()
        val first = fakeVaultMeta(id)
        val second = fakeVaultMeta(id)
        val third = fakeVaultMeta(id)
        files.createVault(first)
        files.writeVaultMeta(second)
        assertArrayEquals(second, root.child(VAULT_META).readBytes())
        assertArrayEquals(first, root.child(VAULT_META_BAK).readBytes())
        files.writeVaultMeta(third)
        assertArrayEquals(third, root.child(VAULT_META).readBytes())
        assertArrayEquals(second, root.child(VAULT_META_BAK).readBytes())
        assertFalse(root.child(VAULT_META_TMP).exists())
    }

    @Test
    fun fallsBackToBackupWhenMetaIsMissing() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val id = FileHeader.newId()
        val old = fakeVaultMeta(id)
        files.createVault(old)
        files.writeVaultMeta(fakeVaultMeta(id))
        root.child(VAULT_META).delete()
        val read = files.readVaultMeta()
        assertTrue(read.fromBackup)
        assertArrayEquals(old, read.bytes)
    }

    @Test
    fun fallsBackToBackupWhenMetaIsCorruptOrTruncated() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val id = FileHeader.newId()
        val old = fakeVaultMeta(id)
        files.createVault(old)
        files.writeVaultMeta(fakeVaultMeta(id))
        val good = root.child(VAULT_META).readBytes()

        root.child(VAULT_META).writeBytes(good.copyOf(50)) // truncated
        assertArrayEquals(old, files.readVaultMeta().bytes)

        root.child(VAULT_META).writeBytes(good.copyOf().also { it[0] = 'X'.code.toByte() }) // bad magic
        assertTrue(files.readVaultMeta().fromBackup)

        root.child(VAULT_META).writeBytes(ByteArray(0))
        assertTrue(files.readVaultMeta().fromBackup)

        root.child(VAULT_META).writeBytes(ByteArray(VaultFiles.MAX_VAULT_META_SIZE + 1)) // oversized
        assertTrue(files.readVaultMeta().fromBackup)
    }

    @Test
    fun noUsableFileIsAnError() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        assertThrows(VaultNotFoundException::class.java) { files.readVaultMeta() }
        assertFalse(files.hasVault())

        root.child(VAULT_META).writeBytes(ByteArray(70))
        assertThrows(VaultFormatException::class.java) { files.readVaultMeta() }
        root.child(VAULT_META_BAK).writeBytes(ByteArray(70))
        assertThrows(VaultFormatException::class.java) { files.readVaultMeta() }
    }

    @Test
    fun corruptCurrentFileDoesNotReplaceGoodBackup() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val id = FileHeader.newId()
        files.createVault(fakeVaultMeta(id))
        files.writeVaultMeta(fakeVaultMeta(id))
        val goodBackup = root.child(VAULT_META_BAK).readBytes()
        root.child(VAULT_META).writeBytes(ByteArray(10))
        val next = fakeVaultMeta(id)
        files.writeVaultMeta(next)
        assertArrayEquals(next, root.child(VAULT_META).readBytes())
        assertArrayEquals(goodBackup, root.child(VAULT_META_BAK).readBytes())
    }

    @Test
    fun staleTmpIsReplacedAndUnparsableBytesAreRefused() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        files.createVault(fakeVaultMeta())
        root.child(VAULT_META_TMP).writeBytes(ByteArray(5000) { 1 })
        val next = fakeVaultMeta(files.readVaultMeta().meta.vaultId)
        files.writeVaultMeta(next)
        assertArrayEquals(next, root.child(VAULT_META).readBytes())
        assertFalse(root.child(VAULT_META_TMP).exists())

        val before = root.child(VAULT_META).readBytes()
        assertThrows(VaultFormatException::class.java) { files.writeVaultMeta(ByteArray(80)) }
        assertArrayEquals(before, root.child(VAULT_META).readBytes())
    }

    /** Kill the write at every step: the folder must always open as the old or the new vault.meta. */
    @Test
    fun crashAtAnyStepLeavesOldOrNewMeta() {
        val id = FileHeader.newId()
        val old = fakeVaultMeta(id)
        val new = fakeVaultMeta(id)
        var step = 0
        var completed = false
        while (!completed) {
            val root = tmp.newFolder()
            VaultFiles(FileVaultStorage(root)).createVault(old)
            val crashing = CrashingStorage(FileVaultStorage(root), failAt = step)
            completed = try {
                VaultFiles(crashing).writeVaultMeta(new)
                true
            } catch (@Suppress("SwallowedException") e: SimulatedCrash) {
                false
            }
            val read = VaultFiles(FileVaultStorage(root)).readVaultMeta().bytes
            val expected = if (completed) new else read
            assertArrayEquals("step $step", expected, read)
            assertTrue("step $step", read.contentEquals(old) || read.contentEquals(new))
            // A second, clean write after the crash must succeed and win.
            val again = fakeVaultMeta(id)
            VaultFiles(FileVaultStorage(root)).writeVaultMeta(again)
            assertArrayEquals(again, VaultFiles(FileVaultStorage(root)).readVaultMeta().bytes)
            step++
        }
        assertTrue("write has several steps", step >= 4)
    }

    @Test
    fun providerErrorsDoNotFallBackToBackup() {
        val root = tmp.newFolder()
        val id = FileHeader.newId()
        VaultFiles(FileVaultStorage(root)).createVault(fakeVaultMeta(id))
        VaultFiles(FileVaultStorage(root)).writeVaultMeta(fakeVaultMeta(id))
        val failing = object : VaultStorage by FileVaultStorage(root) {
            override fun openRead(dir: String, name: String): FileChannel = throw VaultUnavailableException("card gone")
        }
        assertThrows(VaultUnavailableException::class.java) { VaultFiles(failing).readVaultMeta() }
    }

    @Test
    fun vaultIdMismatchIsRejected() {
        val files = newFiles()
        val id = FileHeader.newId()
        files.createVault(fakeVaultMeta(id))
        assertEquals(id.toList(), files.requireVaultId(id).meta.vaultId.toList())
        assertThrows(VaultIdMismatchException::class.java) { files.requireVaultId(FileHeader.newId()) }
    }

    @Test
    fun neverOverwritesAnExistingVault() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        val first = fakeVaultMeta()
        files.createVault(first)
        assertThrows(VaultAlreadyExistsException::class.java) { files.createVault(fakeVaultMeta()) }
        assertArrayEquals(first, root.child(VAULT_META).readBytes())

        // Only a backup left (crash mid-replace) still counts as a vault.
        root.child(VAULT_META).renameTo(root.child(VAULT_META_BAK))
        assertThrows(VaultAlreadyExistsException::class.java) { files.createVault(fakeVaultMeta()) }
    }

    @Test
    fun stagingCleanupDeletesOnlyPartFiles() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        files.createVault(fakeVaultMeta())
        val id = "0123456789abcdef0123456789abcdef"
        root.child(STAGING, VaultFiles.partName(id)).writeBytes(ByteArray(10))
        root.child(STAGING, "x.part").writeBytes(ByteArray(10))
        root.child(ITEMS, VaultFiles.partName(id)).writeBytes(ByteArray(10))
        root.child(ITEMS, VaultFiles.dataName(id)).writeBytes(ByteArray(10))
        root.child(ITEMS, VaultFiles.metaName(id)).writeBytes(ByteArray(10))
        root.child(STAGING, "keep.txt").writeBytes(ByteArray(1))

        assertEquals(3, files.cleanStaging())
        assertEquals(listOf("keep.txt"), FileVaultStorage(root).list(STAGING))
        assertEquals(listOf(VaultFiles.dataName(id), VaultFiles.metaName(id)), FileVaultStorage(root).list(ITEMS))
        assertEquals(0, files.cleanStaging())
    }

    @Test
    fun stagingCleanupDeletesDataAndThumbnailsWithoutMetadata() {
        val root = tmp.newFolder()
        val files = newFiles(root)
        files.createVault(fakeVaultMeta())
        val kept = "0123456789abcdef0123456789abcdef"
        val orphan = "fedcba9876543210fedcba9876543210"
        for (name in listOf(VaultFiles.dataName(kept), VaultFiles.thumbName(kept), VaultFiles.metaName(kept))) {
            root.child(ITEMS, name).writeBytes(ByteArray(10))
        }
        root.child(ITEMS, VaultFiles.dataName(orphan)).writeBytes(ByteArray(10))
        root.child(ITEMS, VaultFiles.thumbName(orphan)).writeBytes(ByteArray(10))
        root.child(ITEMS, "notes.vdat").writeBytes(ByteArray(1)) // not an item id: left alone

        assertEquals(2, files.cleanStaging())
        assertEquals(
            listOf(VaultFiles.dataName(kept), VaultFiles.metaName(kept), VaultFiles.thumbName(kept), "notes.vdat"),
            FileVaultStorage(root).list(ITEMS),
        )
    }

    @Test
    fun fileStorageReportsFreeSpace() {
        val free = FileVaultStorage(tmp.newFolder()).freeBytes()
        assertTrue(free != null && free > 0)
    }

    @Test
    fun readOnlyFolderBlocksWritesButReads() {
        val root = tmp.newFolder()
        val meta = fakeVaultMeta()
        newFiles(root).createVault(meta)
        root.child(STAGING, "a.vdat.part").writeBytes(ByteArray(1))
        val ro = newFiles(root, readOnly = true)
        assertArrayEquals(meta, ro.readVaultMeta().bytes)
        assertThrows(VaultReadOnlyException::class.java) { ro.writeVaultMeta(fakeVaultMeta()) }
        assertThrows(VaultReadOnlyException::class.java) { ro.createVault(fakeVaultMeta()) }
        assertEquals(0, ro.cleanStaging())
        assertTrue(root.child(STAGING, "a.vdat.part").exists())
        assertArrayEquals(meta, root.child(VAULT_META).readBytes())
    }

    @Test
    fun fileStorageRejectsClashesAndBadNames() {
        val storage = FileVaultStorage(tmp.newFolder())
        storage.create(ROOT, "a").close()
        storage.create(ROOT, "b").close()
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { storage.create(ROOT, "a") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { storage.rename(ROOT, "a", "b") }
        assertThrows(FileNotFoundException::class.java) { storage.openRead(ROOT, "zzz") }
        assertThrows(IllegalArgumentException::class.java) { storage.create(ROOT, "../x") }
        assertThrows(IllegalArgumentException::class.java) { storage.create(ROOT, "") }
        assertFalse(storage.delete(ROOT, "zzz"))
        assertEquals(listOf("a", "b"), storage.list(ROOT))
        assertEquals(emptyList<String>(), storage.list("missing"))
    }

    @Test
    fun readBoundedRejectsOversizedFiles() {
        val root = tmp.newFolder()
        root.child("big").writeBytes(ByteArray(101))
        val files = newFiles(root)
        assertEquals(101, files.readSmall(ROOT, "big", 101).size)
        assertThrows(IOException::class.java) { files.readSmall(ROOT, "big", 100) }
    }
}
