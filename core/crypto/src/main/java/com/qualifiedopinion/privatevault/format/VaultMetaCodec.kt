package com.qualifiedopinion.privatevault.format

import com.qualifiedopinion.privatevault.crypto.KdfParams
import com.qualifiedopinion.privatevault.crypto.PinKdf
import java.nio.ByteBuffer

/** Parsed `vault.meta` (planning pack §3.3). */
class VaultMeta(
    val header: FileHeader,
    val kdf: KdfParams,
    salt: ByteArray,
    outerBlob: ByteArray,
    val wrapScheme: Int = VaultMetaCodec.WRAP_KS_PIN_LAYERED,
) {
    private val saltBytes = salt.copyOf()
    private val outerBytes = outerBlob.copyOf()

    init {
        require(header.type == FileType.VAULT_META) { "not a vault.meta header" }
        require(saltBytes.size == PinKdf.SALT_SIZE) { "salt must be ${PinKdf.SALT_SIZE} bytes" }
        require(outerBytes.size in 1..VaultMetaCodec.MAX_OUTER_LENGTH) { "outer blob length out of range" }
    }

    val salt: ByteArray get() = saltBytes.copyOf()
    val outerBlob: ByteArray get() = outerBytes.copyOf()
    val vaultId: ByteArray get() = header.id
}

/**
 * `vault.meta` layout, big-endian:
 *
 * ```
 * 0  32 FileHeader (type 01, vault id)
 * 32 1  kdf id (01 = Argon2id v1.3)
 * 33 4  m_cost KiB (u32)
 * 37 4  t_cost (u32)
 * 41 1  parallelism
 * 42 1  salt len (16)
 * 43 16 salt
 * 59 1  wrap scheme (01 = KS(PIN(DK)))
 * 60 4  outer len N (u32)
 * 64 N  outer blob
 * ```
 *
 * Bytes `[0,60)` are the AAD of both wrap layers, so the header and KDF parameters can't be edited.
 */
object VaultMetaCodec {
    const val AAD_LENGTH = 60
    const val WRAP_KS_PIN_LAYERED = 0x01
    const val MAX_OUTER_LENGTH = 4096
    private const val OUTER_OFFSET = AAD_LENGTH + Int.SIZE_BYTES
    private const val UNSIGNED_BYTE = 0xFF

    /** The AAD (bytes `[0,60)`) for a vault.meta with these fields. Needed before the blob exists. */
    fun aad(header: FileHeader, kdf: KdfParams, salt: ByteArray): ByteArray {
        require(header.type == FileType.VAULT_META) { "not a vault.meta header" }
        require(salt.size == PinKdf.SALT_SIZE) { "salt must be ${PinKdf.SALT_SIZE} bytes" }
        val buf = ByteBuffer.allocate(AAD_LENGTH)
        buf.put(header.encode())
        buf.put(KdfParams.KDF_ARGON2ID_V13.toByte())
        buf.putInt(kdf.memoryKiB)
        buf.putInt(kdf.iterations)
        buf.put(kdf.parallelism.toByte())
        buf.put(PinKdf.SALT_SIZE.toByte())
        buf.put(salt)
        buf.put(WRAP_KS_PIN_LAYERED.toByte())
        return buf.array()
    }

    fun encode(meta: VaultMeta): ByteArray {
        val outer = meta.outerBlob
        val buf = ByteBuffer.allocate(OUTER_OFFSET + outer.size)
        buf.put(aad(meta.header, meta.kdf, meta.salt))
        buf.putInt(outer.size)
        buf.put(outer)
        return buf.array()
    }

    /** Strict parse. Any unknown id, out-of-range value or length mismatch throws [VaultFormatException]. */
    fun decode(bytes: ByteArray): VaultMeta {
        formatCheck(bytes.size >= OUTER_OFFSET) { "vault.meta truncated" }
        val header = FileHeader.decode(bytes)
        formatCheck(header.type == FileType.VAULT_META) { "not a vault.meta file" }
        val buf = ByteBuffer.wrap(bytes)
        buf.position(FileHeader.SIZE)
        val kdfId = buf.get().toInt() and UNSIGNED_BYTE
        formatCheck(kdfId == KdfParams.KDF_ARGON2ID_V13) { "unknown kdf $kdfId" }
        val m = buf.int.toUInt().toLong()
        val t = buf.int.toUInt().toLong()
        val p = buf.get().toInt() and UNSIGNED_BYTE
        formatCheck(m <= KdfParams.MAX_MEMORY_KIB && t <= KdfParams.MAX_ITERATIONS) { "kdf parameters out of range" }
        val kdf = try {
            KdfParams(m.toInt(), t.toInt(), p)
        } catch (e: IllegalArgumentException) {
            throw VaultFormatException("kdf parameters out of range", e)
        }
        val saltLen = buf.get().toInt() and UNSIGNED_BYTE
        formatCheck(saltLen == PinKdf.SALT_SIZE) { "unexpected salt length $saltLen" }
        val salt = ByteArray(PinKdf.SALT_SIZE).also { buf.get(it) }
        val scheme = buf.get().toInt() and UNSIGNED_BYTE
        formatCheck(scheme == WRAP_KS_PIN_LAYERED) { "unknown wrap scheme $scheme" }
        val n = buf.int.toUInt().toLong()
        formatCheck(n in 1..MAX_OUTER_LENGTH) { "outer blob length out of range" }
        formatCheck(bytes.size.toLong() == OUTER_OFFSET + n) { "vault.meta length mismatch" }
        val outer = ByteArray(n.toInt()).also { buf.get(it) }
        return VaultMeta(header, kdf, salt, outer, scheme)
    }
}
