package com.qualifiedopinion.privatevault.vault

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.KeystoreKeyWrapper
import com.qualifiedopinion.privatevault.crypto.PinKdf
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.ITEMS
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.STAGING
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META_BAK
import com.qualifiedopinion.privatevault.vault.VaultStorage.Companion.ROOT
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest

/**
 * SAF paths through a real `ContentResolver` → DocumentsProvider → `ParcelFileDescriptor` stack,
 * using [TestDocumentsProvider] (no picker needed, so this runs in CI).
 * `./gradlew :core:vault:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class SafVaultStorageTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val io = SafIo(context.contentResolver)
    private val base: File get() = TestDocumentsProvider.baseDir(context)
    private val failures = mutableListOf<Throwable>()
    private val keyAliases = mutableListOf<ByteArray>()

    private fun storage(readOnly: Boolean = false, preCheck: () -> Unit = {}) =
        SafVaultStorage(tree, io, { readOnly }, preCheck) { failures += it }

    @Before
    fun clean() {
        TestDocumentsProvider.failAll = false
        TestDocumentsProvider.readOnly = false
        base.deleteRecursively()
        base.mkdirs()
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.failAll = false
        TestDocumentsProvider.readOnly = false
        keyAliases.forEach { KeystoreKeyWrapper.forVault(it).deleteKey() }
        base.deleteRecursively()
    }

    @Test
    fun everyDescriptorIsClosed() {
        val s = storage()
        s.create(ROOT, "f").use { writeFully(it, ByteArray(1000) { 1 }) }
        val before = openFds()
        repeat(300) {
            s.openRead(ROOT, "f").use { ch -> ch.read(ByteBuffer.allocate(10)) }
        }
        repeat(50) { i -> s.create(ROOT, "g$i").close() }
        val after = openFds()
        assertTrue("fds before=$before after=$after", after - before < 10)
    }

    @Test
    fun rwModeDoesNotTruncate() {
        val s = storage()
        s.create(ROOT, "f").use { writeFully(it, ByteArray(100) { 7 }) }
        val entry = io.list(tree, TestDocumentsProvider.ROOT_ID).single { it.name == "f" }
        io.openWrite(io.documentUri(tree, entry.documentId)).use { ch -> writeFully(ch, ByteArray(10) { 9 }) }
        val bytes = File(base, "f").readBytes()
        assertEquals(100, bytes.size)
        assertTrue(bytes.copyOfRange(0, 10).all { it == 9.toByte() })
        assertTrue(bytes.copyOfRange(10, 100).all { it == 7.toByte() })
    }

    @Test
    fun seekableReadThroughDescriptor() {
        val s = storage()
        val data = ByteArray(300_000) { (it * 13).toByte() }
        s.create(ROOT, "f").use { writeFully(it, data) }
        s.openRead(ROOT, "f").use { ch ->
            assertEquals(data.size.toLong(), ch.size())
            ch.position(250_000)
            val buf = ByteBuffer.allocate(100)
            ch.read(buf)
            assertArrayEquals(data.copyOfRange(250_000, 250_100), buf.array())
        }
    }

    @Test
    fun providerRenamesOnClashAndStorageRefusesToContinue() {
        val parent = io.documentUri(tree, TestDocumentsProvider.ROOT_ID)
        val first = io.createDocument(tree, parent, SafIo.MIME_BINARY, "vault.meta")
        val second = io.createDocument(tree, parent, SafIo.MIME_BINARY, "vault.meta")
        assertEquals("vault.meta", first.name)
        assertNotEquals("vault.meta", second.name) // ExternalStorageProvider does the same

        val s = storage()
        s.create(ROOT, "a").close()
        s.create(ROOT, "b").close()
        assertThrows(FileAlreadyExistsException::class.java) { s.create(ROOT, "a") }
        assertThrows(FileAlreadyExistsException::class.java) { s.rename(ROOT, "a", "b") }
        // A file created behind the cache's back is still seen (a miss re-lists).
        File(base, "c").writeBytes(ByteArray(1))
        assertThrows(FileAlreadyExistsException::class.java) { s.create(ROOT, "c") }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun atomicVaultMetaReplaceOverSaf() {
        val files = VaultFiles(storage())
        val id = FileHeader.newId()
        val m1 = fakeMeta(id)
        val m2 = fakeMeta(id)
        files.createVault(m1)
        files.writeVaultMeta(m2)
        assertArrayEquals(m2, File(base, VAULT_META).readBytes())
        assertArrayEquals(m1, File(base, VAULT_META_BAK).readBytes())
        assertEquals(setOf(VAULT_META, VAULT_META_BAK, ITEMS, STAGING, VaultFiles.JOURNAL), base.list()!!.toSet())
        File(base, VAULT_META).delete()
        // A fresh storage (cold cache) falls back to the backup.
        assertArrayEquals(m1, VaultFiles(storage()).readVaultMeta().bytes)
        assertThrows(VaultIdMismatchException::class.java) { VaultFiles(storage()).requireVaultId(FileHeader.newId()) }
    }

    @Test
    fun itemsCreateListDeleteOverSafWithKeystore() {
        val index = VaultIndex()
        val session = VaultSession(index)
        val files = VaultFiles(storage())
        val keys = VaultKeys(sha256Kdf) { KeystoreKeyWrapper.forVault(it) }
        val vaultId = keys.create(files, "246810".toByteArray(), KdfParams.FLOOR, session).also { keyAliases += it }
        val repo = ItemRepository(files, session, index)
        val data = ByteArray(3_000_000) { (it % 251).toByte() }
        val a = repo.create(NewItem(ItemKind.VIDEO, "video/mp4", "a.mp4"), ByteArrayInputStream(data), ByteArray(10))
        val b = repo.create(NewItem(ItemKind.PHOTO, "image/jpeg", "b.jpg"), ByteArrayInputStream(ByteArray(5)), ByteArray(10))
        assertEquals(6, File(base, ITEMS).list()!!.size)
        assertEquals(0, File(base, STAGING).list()!!.size)

        session.lock()
        val files2 = VaultFiles(storage())
        keys.unlock(files2, "246810".toByteArray(), vaultId, session)
        val repo2 = ItemRepository(files2, session, index)
        assertEquals(setOf(a.id, b.id), repo2.loadAll().items.map { it.id }.toSet())
        repo2.openData(a.id).use { ch ->
            ch.position(2_500_000)
            val buf = ByteBuffer.allocate(1000)
            while (buf.hasRemaining()) if (ch.read(buf) < 0) break
            assertArrayEquals(data.copyOfRange(2_500_000, 2_501_000), buf.array())
        }
        repo2.delete(a.id)
        assertEquals(
            setOf(VaultFiles.dataName(b.id), VaultFiles.thumbName(b.id), VaultFiles.metaName(b.id)),
            File(base, ITEMS).list()!!.toSet(),
        )
        assertEquals(listOf(b.id), index.items.value.map { it.id })

        File(base, "$STAGING/${VaultFiles.partName(a.id)}").writeBytes(ByteArray(3))
        assertEquals(1, VaultFiles(storage()).cleanStaging())
        assertEquals(0, File(base, STAGING).list()!!.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun providerFailureMeansVaultGone() {
        val s = storage()
        s.create(ROOT, "f").close()
        s.list(ROOT)
        TestDocumentsProvider.failAll = true
        assertThrows(VaultUnavailableException::class.java) { s.openRead(ROOT, "f") }
        assertThrows(VaultUnavailableException::class.java) { s.list(ROOT) }
        assertThrows(VaultUnavailableException::class.java) { VaultFiles(storage()).readVaultMeta() }
        assertTrue(failures.size >= 3)
    }

    @Test
    fun preCheckRunsBeforeTheProvider() {
        storage().create(ROOT, "f").close()
        val openedBefore = TestDocumentsProvider.opened
        val s = storage(preCheck = { throw VaultUnavailableException("revoked") })
        assertThrows(VaultUnavailableException::class.java) { s.openRead(ROOT, "f") }
        assertEquals(openedBefore, TestDocumentsProvider.opened)
    }

    @Test
    fun readOnlyMountIsDetectedAndBlocksWrites() {
        TestDocumentsProvider.readOnly = true
        val flags = storage().rootFlags()
        assertEquals(0, flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
        val ro = storage(readOnly = true)
        assertThrows(VaultReadOnlyException::class.java) { ro.create(ROOT, "x") }
        assertThrows(VaultReadOnlyException::class.java) { VaultFiles(ro).createVault(fakeMeta(FileHeader.newId())) }
        assertFalse(File(base, "x").exists())
    }

    private val sha256Kdf = PinKdf { pin, salt, _ ->
        MessageDigest.getInstance("SHA-256").run {
            update(pin)
            update(salt)
            digest()
        }
    }

    private fun fakeMeta(id: ByteArray): ByteArray {
        val salt = ByteArray(16) { 3 }
        val blob = ByteArray(80).also { java.security.SecureRandom().nextBytes(it) }
        return com.qualifiedopinion.privatevault.format.VaultMetaCodec.encode(
            com.qualifiedopinion.privatevault.format.VaultMeta(FileHeader(FileType.VAULT_META, id), KdfParams.FLOOR, salt, blob),
        )
    }

    private fun writeFully(ch: java.nio.channels.WritableByteChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }

    private fun openFds(): Int = File("/proc/self/fd").list()?.size ?: 0
}
