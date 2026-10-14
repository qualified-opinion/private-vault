package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.KeyWrapper
import com.qualifiedopinion.privatevault.crypto.KeyWrapperKeyMissingException
import com.qualifiedopinion.privatevault.crypto.WrongPinException
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.VaultMetaCodec
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.VAULT_META_BAK
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VaultKeysTest {
    @get:Rule
    val tmp = vaultTempFolder()

    private val device = SoftwareKeyWrapper()
    private val keys = VaultKeys(FakeKdf()) { device }
    private val session = VaultSession(VaultIndex())

    @Test
    fun createThenLockThenUnlock() {
        val root = tmp.newFolder()
        val files = VaultFiles(FileVaultStorage(root))
        val id = keys.create(files, pin("123456"), KdfParams.FLOOR, session)
        assertTrue(session.isUnlocked.value)
        assertArrayEquals(id, session.vaultId)

        // vault.meta parses as v1 with the §3.2 header.
        val bytes = root.child(VAULT_META).readBytes()
        val header = FileHeader.decode(bytes)
        assertEquals(FileType.VAULT_META, header.type)
        assertArrayEquals(id, header.id)
        assertEquals("PVLT", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, bytes[5].toInt())
        assertEquals(KdfParams.FLOOR, VaultMetaCodec.decode(bytes).kdf)

        session.lock()
        assertFalse(session.isUnlocked.value)
        assertThrows(VaultLockedException::class.java) { session.crypto() }

        assertThrows(WrongPinException::class.java) { keys.unlock(files, pin("654321"), id, session) }
        assertFalse(session.isUnlocked.value)

        assertArrayEquals(id, keys.unlock(files, pin("123456"), id, session))
        assertTrue(session.isUnlocked.value)
    }

    @Test
    fun pinArraysAreZeroized() {
        val files = VaultFiles(FileVaultStorage(tmp.newFolder()))
        val p1 = pin("12345678")
        val id = keys.create(files, p1, KdfParams.FLOOR, session)
        assertTrue(p1.all { it == 0.toByte() })
        val p2 = pin("00000000")
        assertThrows(WrongPinException::class.java) { keys.unlock(files, p2, id, session) }
        assertTrue(p2.all { it == 0.toByte() })
    }

    @Test
    fun otherVaultIsRejectedBeforeAnyKeyIsUsed() {
        val a = VaultFiles(FileVaultStorage(tmp.newFolder()))
        val b = VaultFiles(FileVaultStorage(tmp.newFolder()))
        val idA = keys.create(a, pin("111111"), KdfParams.FLOOR, session)
        keys.create(b, pin("111111"), KdfParams.FLOOR, session)
        session.lock()
        var keyUsed = false
        val spying = VaultKeys(FakeKdf()) {
            keyUsed = true
            device
        }
        assertThrows(VaultIdMismatchException::class.java) { spying.unlock(b, pin("111111"), idA, session) }
        assertFalse(keyUsed)
        assertFalse(session.isUnlocked.value)
    }

    @Test
    fun createNeverOverwritesAnExistingVault() {
        val root = tmp.newFolder()
        val files = VaultFiles(FileVaultStorage(root))
        keys.create(files, pin("123456"), KdfParams.FLOOR, session)
        val before = root.child(VAULT_META).readBytes()
        session.lock()
        assertThrows(VaultAlreadyExistsException::class.java) {
            keys.create(files, pin("999999"), KdfParams.FLOOR, session)
        }
        assertArrayEquals(before, root.child(VAULT_META).readBytes())
        assertFalse(session.isUnlocked.value)
    }

    @Test
    fun readOnlyFolderCannotHostANewVault() {
        val root = tmp.newFolder()
        assertThrows(VaultReadOnlyException::class.java) {
            keys.create(VaultFiles(FileVaultStorage(root, readOnly = true)), pin("123456"), KdfParams.FLOOR, session)
        }
        assertEquals(0, root.list()!!.size)
    }

    @Test
    fun unlocksFromBackupWhenMetaIsDamaged() {
        val root = tmp.newFolder()
        val files = VaultFiles(FileVaultStorage(root))
        val id = keys.create(files, pin("123456"), KdfParams.FLOOR, session)
        files.writeVaultMeta(root.child(VAULT_META).readBytes()) // now there is a .bak
        root.child(VAULT_META).writeBytes(ByteArray(3))
        session.lock()
        keys.unlock(files, pin("123456"), id, session)
        assertTrue(session.isUnlocked.value)
        assertTrue(root.child(VAULT_META_BAK).exists())
    }

    @Test
    fun anotherDeviceCannotUnlock() {
        val files = VaultFiles(FileVaultStorage(tmp.newFolder()))
        val id = keys.create(files, pin("123456"), KdfParams.FLOOR, session)
        session.lock()
        val otherPhone = VaultKeys(FakeKdf()) { SoftwareKeyWrapper() }
        assertThrows(java.security.GeneralSecurityException::class.java) {
            otherPhone.unlock(files, pin("123456"), id, session)
        }
        val reinstalled = VaultKeys(FakeKdf()) {
            object : KeyWrapper {
                override fun wrap(plaintext: ByteArray, aad: ByteArray) = throw UnsupportedOperationException()
                override fun unwrap(blob: ByteArray, aad: ByteArray) = throw KeyWrapperKeyMissingException("gone")
            }
        }
        assertThrows(KeyWrapperKeyMissingException::class.java) {
            reinstalled.unlock(files, pin("123456"), id, session)
        }
        assertFalse(session.isUnlocked.value)
    }
}
