package com.qualifiedopinion.privatevault

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.KeystoreKeyWrapper
import com.qualifiedopinion.privatevault.crypto.WrongPinException
import com.qualifiedopinion.privatevault.format.toHex
import com.qualifiedopinion.privatevault.vault.FileVaultStorage
import com.qualifiedopinion.privatevault.vault.ItemKind
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.LocationRecord
import com.qualifiedopinion.privatevault.vault.LocationStore
import com.qualifiedopinion.privatevault.vault.NewItem
import com.qualifiedopinion.privatevault.vault.VaultFiles
import com.qualifiedopinion.privatevault.vault.VaultIndex
import com.qualifiedopinion.privatevault.vault.VaultSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Release-build unlock verification (planning pack §6 PR3). `testBuildType` is `releaseCheck`, so this
 * runs against the R8-minified app with the release rules: real argon2kt (JNI) and the real Android
 * Keystore, through the app's own [AppGraph] objects, on a temp-dir [FileVaultStorage] (no SAF picker).
 *
 * `./gradlew :app:connectedReleaseCheckAndroidTest` (CI: emulator job). Numbers: `adb logcat -s PV-RELEASE`.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseUnlockTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as PrivateVaultApp).graph
    private val root = File(context.cacheDir, "release-check-vault") // ciphertext only; deleted below
    private var vaultId: ByteArray? = null

    @After
    fun cleanUp() {
        vaultId?.let { KeystoreKeyWrapper.forVault(it).deleteKey() }
        root.deleteRecursively()
    }

    @Test
    fun runsAgainstMinifiedNonDebuggableBuild() {
        assertEquals(
            "releaseCheck must not be debuggable",
            0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE,
        )
        // R8 renamed a class that nothing keeps by name: proof that this is shrunk/obfuscated code.
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.qualifiedopinion.privatevault.ui.VaultViewModel")
        }
    }

    @Test
    fun argon2ktWorksAfterR8() {
        // phc-winner-argon2 Argon2id v1.3 vector ("password"/"somesalt", m=64 MiB, t=2, p=1), as in :core:crypto.
        val kek = graph.kdf.deriveKey("password".toByteArray(), "somesalt".toByteArray(), KdfParams(65536, 2, 1))
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", kek.toHex())
        val t0 = System.nanoTime()
        val params = graph.kdf.calibrate()
        Log.i(TAG, "calibrated $params in ${(System.nanoTime() - t0) / 1_000_000} ms (minified)")
        assertTrue(params.meetsFloor())
    }

    @Test
    fun createLockUnlockWithRightAndWrongPin() {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val files = VaultFiles(FileVaultStorage(root))
        val index = VaultIndex()
        val session = VaultSession(index)
        val id = graph.keys.create(files, "482916".toByteArray(), KdfParams.FLOOR, session)
        vaultId = id
        assertTrue(session.isUnlocked.value)

        // One item, so Tink streaming and the kotlinx-serialization .vmeta codec also run minified.
        val data = ByteArray(2_500_000) { (it % 253).toByte() }
        val repo = ItemRepository(files, session, index)
        val item = repo.create(NewItem(ItemKind.PHOTO, "image/jpeg", "a.jpg", w = 3, h = 2), ByteArrayInputStream(data), ByteArray(7))

        session.lock()
        assertFalse(session.isUnlocked.value)
        assertEquals(0, index.size)

        assertThrows(WrongPinException::class.java) {
            graph.keys.unlock(files, "482917".toByteArray(), id, session)
        }
        assertFalse(session.isUnlocked.value)

        val t0 = System.nanoTime()
        graph.keys.unlock(files, "482916".toByteArray(), id, session)
        Log.i(TAG, "unlock at floor params: ${(System.nanoTime() - t0) / 1_000_000} ms (minified)")
        assertTrue(session.isUnlocked.value)

        val loaded = repo.loadAll()
        assertEquals(listOf(item), loaded.items)
        repo.openData(item.id).use { ch ->
            val out = ByteBuffer.allocate(data.size)
            while (out.hasRemaining()) if (ch.read(out) < 0) break
            assertArrayEquals(data, out.array())
        }
        session.lock()
    }

    @Test
    fun locationJsonWorksAfterR8() {
        val store = LocationStore(File(root, "location.json"))
        val record = LocationRecord(treeUri = "content://x/tree/1234-ABCD%3AV", volume = "1234-ABCD", vaultId = "ab".repeat(16))
        store.save(record)
        assertEquals(record, store.load())
        assertEquals(ItemRepository.idFromHex(record.vaultId).toHex(), record.vaultId)
    }

    private companion object {
        const val TAG = "PV-RELEASE"
    }
}
