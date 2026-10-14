package com.qualifiedopinion.privatevault.format

import com.qualifiedopinion.privatevault.crypto.KdfParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class VaultMetaCodecTest {
    private val vaultId = ByteArray(16) { (0xA0 + it).toByte() }
    private val salt = ByteArray(16) { it.toByte() }
    private val outer = ByteArray(100) { (it * 3).toByte() }
    private val kdf = KdfParams(65536, 3, 1)
    private val meta = VaultMeta(FileHeader(FileType.VAULT_META, vaultId), kdf, salt, outer)
    private val encoded = VaultMetaCodec.encode(meta)

    @Test
    fun encodesTheDocumentedLayout() {
        assertEquals(64 + outer.size, encoded.size)
        val b = ByteBuffer.wrap(encoded)
        assertEquals(FileHeader(FileType.VAULT_META, vaultId), FileHeader.decode(encoded))
        assertEquals(0x01, encoded[32].toInt())
        assertEquals(65536, b.getInt(33))
        assertEquals(3, b.getInt(37))
        assertEquals(1, encoded[41].toInt())
        assertEquals(16, encoded[42].toInt())
        assertArrayEquals(salt, encoded.copyOfRange(43, 59))
        assertEquals(0x01, encoded[59].toInt())
        assertEquals(outer.size, b.getInt(60))
        assertArrayEquals(outer, encoded.copyOfRange(64, encoded.size))
    }

    @Test
    fun aadIsTheFirst60Bytes() {
        assertArrayEquals(encoded.copyOfRange(0, 60), VaultMetaCodec.aad(meta.header, kdf, salt))
    }

    @Test
    fun roundTrips() {
        val back = VaultMetaCodec.decode(encoded)
        assertEquals(meta.header, back.header)
        assertEquals(kdf, back.kdf)
        assertArrayEquals(salt, back.salt)
        assertArrayEquals(outer, back.outerBlob)
        assertArrayEquals(vaultId, back.vaultId)
        assertEquals(VaultMetaCodec.WRAP_KS_PIN_LAYERED, back.wrapScheme)
    }

    @Test
    fun rejectsUnknownMagicVersionAndType() {
        assertRejected { it[0] = 0 }
        assertRejected { it[5] = 2 } // format major 2
        assertRejected { it[4] = FileType.DATA.code.toByte() }
    }

    @Test
    fun rejectsUnknownKdfAndWrapScheme() {
        assertRejected { it[32] = 0x02 }
        assertRejected { it[59] = 0x02 } // 02 is the recovery scheme; not valid in vault.meta
        assertRejected { it[59] = 0x00 }
    }

    @Test
    fun rejectsOutOfRangeKdfParameters() {
        assertRejected { ByteBuffer.wrap(it).putInt(33, KdfParams.MAX_MEMORY_KIB + 1) }
        assertRejected { ByteBuffer.wrap(it).putInt(33, -1) } // u32 4 GiB
        assertRejected { ByteBuffer.wrap(it).putInt(33, 4) }
        assertRejected { ByteBuffer.wrap(it).putInt(37, 0) }
        assertRejected { ByteBuffer.wrap(it).putInt(37, -1) }
        assertRejected { it[41] = 0 }
        assertRejected { it[42] = 15 }
    }

    @Test
    fun rejectsLengthMismatch() {
        assertThrows(VaultFormatException::class.java) { VaultMetaCodec.decode(encoded + 0) }
        assertThrows(VaultFormatException::class.java) { VaultMetaCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(VaultFormatException::class.java) { VaultMetaCodec.decode(encoded.copyOf(63)) }
        assertRejected { ByteBuffer.wrap(it).putInt(60, 0) }
        assertRejected { ByteBuffer.wrap(it).putInt(60, -1) }
        assertRejected { ByteBuffer.wrap(it).putInt(60, outer.size + 1) }
    }

    private fun assertRejected(edit: (ByteArray) -> Unit) {
        val bytes = encoded.copyOf()
        edit(bytes)
        assertThrows(VaultFormatException::class.java) { VaultMetaCodec.decode(bytes) }
    }
}
