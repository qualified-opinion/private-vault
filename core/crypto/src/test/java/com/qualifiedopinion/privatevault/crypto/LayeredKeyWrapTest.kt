package com.qualifiedopinion.privatevault.crypto

import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.VaultFormatException
import com.qualifiedopinion.privatevault.format.VaultMetaCodec
import com.qualifiedopinion.privatevault.format.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

class LayeredKeyWrapTest {
    private val vaultId = FileHeader.newId()
    private val dk = VaultCrypto.newDataKeyset()
    private val keystore = SoftwareKeyWrapper()

    private fun pin(s: String = "123456") = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun bouncyCastleArgon2idMatchesReferenceVector() {
        // phc-winner-argon2 test vector: Argon2id v1.3, t=2, m=256 KiB, p=1, "password"/"somesalt".
        val out = BouncyCastleArgon2Kdf().deriveKey(
            "password".toByteArray(),
            "somesalt".toByteArray(),
            KdfParams(256, 2, 1),
        )
        assertEquals("9dfeb910e80bad0311fee20f9c0e2b12c17987b4cac90c2ef54d5b3021c68bfe", out.toHex())
    }

    @Test
    fun roundTripsWithArgon2idAtTheFloor() {
        val wrap = LayeredKeyWrap(BouncyCastleArgon2Kdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val back = wrap.unwrap(meta, pin())
        assertTrue(dk.equalsKeyset(back))
        assertEquals(KdfParams.FLOOR, VaultMetaCodec.decode(meta).kdf)
    }

    @Test
    fun wrongPinFails() {
        val wrap = LayeredKeyWrap(BouncyCastleArgon2Kdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, pin("123456"), KdfParams.FLOOR)
        assertThrows(WrongPinException::class.java) { wrap.unwrap(meta, pin("123457")) }
    }

    @Test
    fun fakeKeyWrapperRoundTripsAndCharArrayPinWorks() {
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, "98765432".toCharArray(), KdfParams.FLOOR)
        assertTrue(dk.equalsKeyset(wrap.unwrap(meta, "98765432".toCharArray())))
        assertTrue(dk.equalsKeyset(wrap.unwrap(meta, pin("98765432"))))
    }

    @Test
    fun outerLayerIsBoundToTheFirst60Bytes() {
        var seenAad: ByteArray? = null
        val recording = object : KeyWrapper by keystore {
            override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
                seenAad = aad.copyOf()
                return keystore.wrap(plaintext, aad)
            }
        }
        val meta = LayeredKeyWrap(FakeKdf(), { recording }).wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        assertArrayEquals(meta.copyOfRange(0, 60), seenAad)
    }

    @Test
    fun tamperedKdfParametersFail() {
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val edits = mapOf<String, (ByteArray) -> Unit>(
            "m_cost" to { ByteBuffer.wrap(it).putInt(33, KdfParams.FLOOR_MEMORY_KIB * 2) },
            "t_cost" to { ByteBuffer.wrap(it).putInt(37, 3) },
            "parallelism" to { it[41] = 2 },
            "salt" to { it[50] = (it[50] + 1).toByte() },
            "vault id" to { it[10] = (it[10] + 1).toByte() },
            "outer blob" to { it[70] = (it[70] + 1).toByte() },
        )
        for ((name, edit) in edits) {
            val tampered = meta.copyOf().also(edit)
            assertThrows(name, GeneralSecurityException::class.java) { wrap.unwrap(tampered, pin()) }
        }
    }

    @Test
    fun tamperedKdfParametersFailInTheInnerLayerEvenIfTheOuterIsRewrapped() {
        // Models an attacker who can use the Keystore key (rooted device): they can re-wrap the outer
        // layer with tampered parameters, but the PIN layer is still bound to the original AAD.
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val parsed = VaultMetaCodec.decode(meta)
        val inner = keystore.unwrap(parsed.outerBlob, meta.copyOfRange(0, 60))

        val tampered = meta.copyOf()
        ByteBuffer.wrap(tampered).putInt(37, KdfParams.FLOOR_ITERATIONS + 1)
        val outer = keystore.wrap(inner, tampered.copyOfRange(0, 60))
        val forged = tampered.copyOfRange(0, 60) + ByteBuffer.allocate(4).putInt(outer.size).array() + outer

        assertThrows(WrongPinException::class.java) { wrap.unwrap(forged, pin()) }
    }

    @Test
    fun anotherDevicesKeystoreKeyFails() {
        val meta = LayeredKeyWrap(FakeKdf(), { keystore }).wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val otherDevice = LayeredKeyWrap(FakeKdf(), { SoftwareKeyWrapper() })
        assertThrows(GeneralSecurityException::class.java) { otherDevice.unwrap(meta, pin()) }
    }

    @Test
    fun missingKeystoreKeyIsReported() {
        val meta = LayeredKeyWrap(FakeKdf(), { keystore }).wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val gone = object : KeyWrapper by keystore {
            override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray =
                throw KeyWrapperKeyMissingException("gone")
        }
        assertThrows(KeyWrapperKeyMissingException::class.java) {
            LayeredKeyWrap(FakeKdf(), { gone }).unwrap(meta, pin())
        }
    }

    @Test
    fun unknownVersionOrMagicIsRejected() {
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val meta = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        for (edit in listOf<(ByteArray) -> Unit>({ it[0] = 0x51 }, { it[5] = 2 }, { it[32] = 2 }, { it[59] = 2 })) {
            val bad = meta.copyOf().also(edit)
            assertThrows(VaultFormatException::class.java) { wrap.unwrap(bad, pin()) }
        }
    }

    @Test
    fun keyWrapperIsChosenByVaultId() {
        val seen = mutableListOf<String>()
        val wrap = LayeredKeyWrap(FakeKdf(), { id -> seen += KeystoreKeyWrapper.aliasFor(id); keystore })
        wrap.unwrap(wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR), pin())
        assertEquals(listOf("pv_kek_" + vaultId.toHex(), "pv_kek_" + vaultId.toHex()), seen)
    }

    @Test
    fun pinAndKekAreZeroized() {
        val kdf = FakeKdf()
        val wrap = LayeredKeyWrap(kdf, { keystore })
        val p1 = pin("246810")
        val meta = wrap.wrap(vaultId, dk, p1, KdfParams.FLOOR)
        val p2 = pin("246810")
        wrap.unwrap(meta, p2)
        val c1 = "246810".toCharArray()
        wrap.unwrap(meta, c1)
        val p3 = pin("000000")
        assertThrows(WrongPinException::class.java) { wrap.unwrap(meta, p3) }

        for (a in listOf(p1, p2, p3)) assertTrue(a.all { it == 0.toByte() })
        assertTrue(c1.all { it == '\u0000' })
        assertEquals(4, kdf.issuedKeys.size)
        for (k in kdf.issuedKeys) assertTrue("KEK zeroized", k.all { it == 0.toByte() })
        for (p in kdf.seenPins) assertTrue("PIN bytes zeroized", p.all { it == 0.toByte() })
    }

    @Test
    fun wrapRefusesWeakParametersAndBadPins() {
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val weak = listOf(KdfParams(KdfParams.FLOOR_MEMORY_KIB - 1, 2), KdfParams(KdfParams.FLOOR_MEMORY_KIB, 1))
        for (p in weak) {
            assertThrows(IllegalArgumentException::class.java) { wrap.wrap(vaultId, dk, pin(), p) }
        }
        for (bad in listOf("12345", "1234567890123", "12345a", "123 456")) {
            val b = pin(bad)
            assertThrows(bad, IllegalArgumentException::class.java) { wrap.wrap(vaultId, dk, b, KdfParams.FLOOR) }
            assertTrue("rejected PIN still zeroized", b.all { it == 0.toByte() })
        }
    }

    @Test
    fun eachWrapUsesAFreshSaltAndIv() {
        val wrap = LayeredKeyWrap(FakeKdf(), { keystore })
        val a = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        val b = wrap.wrap(vaultId, dk, pin(), KdfParams.FLOOR)
        assertFalse(a.copyOfRange(43, 59).contentEquals(b.copyOfRange(43, 59)))
        assertFalse(a.copyOfRange(64, a.size).contentEquals(b.copyOfRange(64, b.size)))
    }
}
