package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.format.VaultFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** VaultIndex, ItemMetaCodec, LocationStore, TreeVolume and AvailabilityRules. */
class SmallPartsTest {
    @get:Rule
    val tmp = vaultTempFolder()

    private val idA = "a".repeat(32)
    private val idB = "b".repeat(32)

    private fun meta(id: String, importedAt: Long, name: String = "x.jpg") = ItemMeta(
        id = id,
        kind = ItemKind.PHOTO,
        mime = "image/jpeg",
        name = name,
        size = 1,
        sha256 = "00",
        importedAt = importedAt,
    )

    @Test
    fun indexKeepsNewestFirstAndReplacesById() {
        val index = VaultIndex()
        index.add(meta(idA, 1))
        index.add(meta(idB, 2))
        assertEquals(listOf(idB, idA), index.items.value.map { it.id })
        index.add(meta(idA, 3))
        assertEquals(listOf(idA, idB), index.items.value.map { it.id })
        assertEquals(2, index.size)
        index.remove(idB)
        assertEquals(listOf(idA), index.items.value.map { it.id })
        assertNull(index[idB])
        index.replaceAll(listOf(meta(idB, 5), meta(idB, 5), meta(idA, 1)))
        assertEquals(2, index.size)
        index.clear()
        assertEquals(0, index.size)
    }

    @Test
    fun itemMetaJsonIsStrict() {
        val m = meta(idA, 7, name = "VID_2024.mp4")
            .copy(kind = ItemKind.VIDEO, durMs = 612000, w = 1920, h = 1080, rot = 90)
        val bytes = ItemMetaCodec.encode(m)
        assertTrue(String(bytes).startsWith("{\"v\":1,"))
        assertEquals(m, ItemMetaCodec.decode(bytes, idA))

        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode(bytes, idB) }
        val v2 = String(bytes).replace("\"v\":1", "\"v\":2").toByteArray()
        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode(v2, idA) }
        val extra = String(bytes).replace("{\"v\":1,", "{\"v\":1,\"evil\":true,").toByteArray()
        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode(extra, idA) }
        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode("{".toByteArray(), idA) }
        val badKind = String(bytes).replace("VIDEO", "AUDIO").toByteArray()
        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode(badKind, idA) }
        val tooBig = ByteArray(ItemMetaCodec.MAX_SIZE + 1)
        assertThrows(VaultFormatException::class.java) { ItemMetaCodec.decode(tooBig, idA) }
    }

    @Test
    fun itemMetaToStringLeaksNoNameOrHash() {
        val s = meta(idA, 1, name = "secret-name.jpg").toString()
        assertFalse(s.contains("secret"))
        assertFalse(s.contains(idA))
        assertFalse(NewItem(ItemKind.PHOTO, "image/jpeg", "secret.jpg").toString().contains("secret"))
        val location = LocationRecord(treeUri = "content://x/tree/1%3ASecret", volume = "1234-ABCD", vaultId = idA)
        assertFalse(location.toString().contains("Secret"))
    }

    @Test
    fun locationStoreRoundTripsAndSurvivesCorruption() {
        val file = File(tmp.newFolder(), "location.json")
        val store = LocationStore(file)
        assertNull(store.load())
        val tree = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AVault"
        val r = LocationRecord(treeUri = tree, volume = "1234-ABCD", vaultId = idA)
        store.save(r)
        assertEquals(r, store.load())
        assertFalse(File(file.parentFile, "location.json.tmp").exists())
        file.writeText("{not json")
        assertNull(store.load())
        file.writeText("{\"v\":2,\"treeUri\":\"x\",\"volume\":\"primary\",\"vaultId\":\"$idA\"}")
        assertNull(store.load())
        store.save(r)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun treeVolumeParsing() {
        val esp = TreeVolume.EXTERNAL_STORAGE_AUTHORITY
        assertEquals(TreeVolume("1234-ABCD"), TreeVolume.parse(esp, "1234-abcd:Vault"))
        assertTrue(TreeVolume.parse(esp, "primary:Vault")!!.isPrimary)
        assertTrue(TreeVolume.parse(esp, "primary:")!!.isPrimary)
        assertTrue(TreeVolume.parse(esp, "home:Vault")!!.isPrimary)
        assertFalse(TreeVolume.parse(esp, "1234-ABCD:")!!.isPrimary)
        assertNull(TreeVolume.parse(esp, "nocolon"))
        assertNull(TreeVolume.parse(esp, ":x"))
        assertNull(TreeVolume.parse("com.android.providers.downloads.documents", "raw:/x"))
        assertNull(TreeVolume.parse("com.google.android.apps.docs.storage", "primary:x"))
        assertNull(TreeVolume.parse(null, "primary:x"))
    }

    @Test
    fun availabilityRules() {
        val ok = VaultAvailability.Available(readOnly = false)
        val ro = VaultAvailability.Available(readOnly = true)
        assertEquals(ok, AvailabilityRules.quick(Grant.READ_WRITE, "mounted"))
        assertEquals(ro, AvailabilityRules.quick(Grant.READ_WRITE, "mounted_ro"))
        assertEquals(ro, AvailabilityRules.quick(Grant.READ_ONLY, "mounted"))
        val noPerm = VaultAvailability.Unavailable(UnavailableReason.NO_PERMISSION)
        val noVol = VaultAvailability.Unavailable(UnavailableReason.VOLUME_MISSING)
        assertEquals(noPerm, AvailabilityRules.quick(Grant.NONE, "mounted"))
        for (state in listOf(null, "ejecting", "unmounted", "removed", "bad_removal", "checking")) {
            assertEquals(state, noVol, AvailabilityRules.quick(Grant.READ_WRITE, state))
        }
        // A quick check can only make things worse.
        val wrong = VaultAvailability.Unavailable(UnavailableReason.WRONG_VAULT)
        assertEquals(wrong, AvailabilityRules.mergeQuick(wrong, ok))
        assertEquals(noVol, AvailabilityRules.mergeQuick(ok, noVol))
        assertEquals(ro, AvailabilityRules.mergeQuick(ro, ok))
        assertEquals(ro, AvailabilityRules.mergeQuick(ok, ro))
        assertEquals(VaultAvailability.Unknown, AvailabilityRules.mergeQuick(VaultAvailability.Unknown, ok))

        // A check that finished after the vault was reported gone can't bring it back; a worse result still counts.
        assertEquals(noVol, AvailabilityRules.mergeChecked(noVol, ok, goneDuringCheck = true))
        assertEquals(wrong, AvailabilityRules.mergeChecked(noVol, wrong, goneDuringCheck = true))
        assertEquals(ok, AvailabilityRules.mergeChecked(noVol, ok, goneDuringCheck = false))

        assertTrue(AvailabilityRules.broadcastConcerns("1234-ABCD", "/storage/1234-abcd"))
        assertTrue(AvailabilityRules.broadcastConcerns("1234-ABCD", "/storage/1234-ABCD/"))
        assertFalse(AvailabilityRules.broadcastConcerns("1234-ABCD", "/storage/9999-0000"))
        assertTrue(AvailabilityRules.broadcastConcerns("1234-ABCD", null))
        assertFalse(AvailabilityRules.broadcastConcerns("primary", "/storage/1234-ABCD"))
        assertTrue(AvailabilityRules.broadcastConcerns("primary", "/storage/emulated/0"))
    }

    @Test
    fun itemIdHelpers() {
        assertTrue(VaultFiles.isItemId(idA))
        assertFalse(VaultFiles.isItemId("A".repeat(32)))
        assertFalse(VaultFiles.isItemId("a".repeat(31)))
        assertEquals(16, ItemRepository.idFromHex("0f".repeat(16)).size)
        assertEquals(0x0f.toByte(), ItemRepository.idFromHex("0f".repeat(16))[3])
        assertThrows(IllegalArgumentException::class.java) { ItemRepository.idFromHex("zz") }
    }
}
