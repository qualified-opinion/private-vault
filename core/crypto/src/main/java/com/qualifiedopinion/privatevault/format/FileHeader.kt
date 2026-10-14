package com.qualifiedopinion.privatevault.format

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.security.SecureRandom

/** File type byte at header offset 4 (planning pack §3.2). */
@Suppress("MagicNumber") // the codes are the format
enum class FileType(val code: Int) {
    VAULT_META(0x01),
    DATA(0x02),
    THUMB(0x03),
    META(0x04),
    JOURNAL(0x05),
    RECOVERY(0x06),
    ;

    companion object {
        fun fromCode(code: Int): FileType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The 32-byte header shared by every vault file (planning pack §3.2), big-endian:
 *
 * ```
 * 0  4  magic "PVLT"
 * 4  1  file type
 * 5  1  format major (1)
 * 6  2  flags (0, reserved)
 * 8  16 vault id (types 01, 05, 06) or item id (02–04)
 * 24 8  reserved (0)
 * ```
 *
 * The encoded header is the Tink AAD for streaming files, so it binds type, version and id.
 * Decoding rejects anything this reader does not fully understand; it never guesses.
 */
class FileHeader(val type: FileType, id: ByteArray) {
    private val idBytes: ByteArray

    init {
        require(id.size == ID_SIZE) { "id must be $ID_SIZE bytes" }
        idBytes = id.copyOf()
    }

    /** A copy of the 16-byte vault or item id. */
    val id: ByteArray get() = idBytes.copyOf()

    /** The id as 32 lowercase hex characters, as used in file names. */
    val idHex: String get() = idBytes.toHex()

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.putInt(MAGIC)
        buf.put(type.code.toByte())
        buf.put(FORMAT_MAJOR.toByte())
        buf.putShort(0)
        buf.put(idBytes)
        buf.putLong(0)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean =
        other is FileHeader && other.type == type && other.idBytes.contentEquals(idBytes)

    override fun hashCode(): Int = 31 * type.hashCode() + idBytes.contentHashCode()

    override fun toString(): String = "FileHeader(type=$type)"

    companion object {
        const val SIZE = 32
        const val ID_SIZE = 16
        const val FORMAT_MAJOR = 1

        /** "PVLT". */
        const val MAGIC = 0x50564C54

        private const val TYPE_OFFSET = 4
        private const val MAJOR_OFFSET = 5
        private const val FLAGS_OFFSET = 6
        private const val ID_OFFSET = 8
        private const val RESERVED_OFFSET = 24
        private const val UNSIGNED_BYTE = 0xFF

        private val secureRandom by lazy { SecureRandom() }

        /** A new random 16-byte vault or item id. */
        fun newId(random: SecureRandom = secureRandom): ByteArray = ByteArray(ID_SIZE).also(random::nextBytes)

        /** Decodes [SIZE] bytes at [offset]. Throws [VaultFormatException] on anything unexpected. */
        fun decode(bytes: ByteArray, offset: Int = 0): FileHeader {
            formatCheck(offset >= 0 && bytes.size - offset >= SIZE) { "header truncated" }
            val buf = ByteBuffer.wrap(bytes, offset, SIZE)
            formatCheck(buf.getInt(offset) == MAGIC) { "bad magic" }
            val major = bytes[offset + MAJOR_OFFSET].toInt() and UNSIGNED_BYTE
            formatCheck(major == FORMAT_MAJOR) { "unsupported format major $major" }
            val typeCode = bytes[offset + TYPE_OFFSET].toInt() and UNSIGNED_BYTE
            val type = FileType.fromCode(typeCode)
            formatCheck(type != null) { "unknown file type $typeCode" }
            formatCheck(buf.getShort(offset + FLAGS_OFFSET).toInt() == 0) { "unknown flags" }
            formatCheck(buf.getLong(offset + RESERVED_OFFSET) == 0L) { "reserved bytes not zero" }
            return FileHeader(type!!, bytes.copyOfRange(offset + ID_OFFSET, offset + ID_OFFSET + ID_SIZE))
        }

        /** Reads exactly [SIZE] header bytes from the channel's current position. */
        fun readBytes(channel: ReadableByteChannel): ByteArray {
            val buf = ByteBuffer.allocate(SIZE)
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) throw VaultFormatException("header truncated")
            }
            return buf.array()
        }

        /** Reads exactly [SIZE] header bytes from the stream. */
        fun readBytes(input: InputStream): ByteArray {
            val bytes = ByteArray(SIZE)
            var n = 0
            while (n < SIZE) {
                val r = input.read(bytes, n, SIZE - n)
                if (r < 0) throw VaultFormatException("header truncated")
                n += r
            }
            return bytes
        }
    }
}

/** Lowercase hex, used for file names and the Keystore alias. */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(Hex.DIGITS[(b.toInt() shr Hex.NIBBLE_BITS) and Hex.NIBBLE_MASK])
        sb.append(Hex.DIGITS[b.toInt() and Hex.NIBBLE_MASK])
    }
    return sb.toString()
}

private object Hex {
    const val DIGITS = "0123456789abcdef"
    const val NIBBLE_BITS = 4
    const val NIBBLE_MASK = 0x0F
}
