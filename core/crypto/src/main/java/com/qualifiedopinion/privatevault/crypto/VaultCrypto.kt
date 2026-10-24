package com.qualifiedopinion.privatevault.crypto

import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.VaultFormatException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.security.GeneralSecurityException

/**
 * Streaming encryption of vault files with the data keyset (DK), planning pack §3.4.
 *
 * File = 32-byte [FileHeader] ‖ Tink `AES256_GCM_HKDF_1MB` ciphertext. The Tink AAD is the encoded
 * header, which binds file type, format version and item/vault id.
 *
 * Decryption takes the header the caller expects (type and id from the file name or index). The
 * file's header must parse and match it exactly, so a renamed or swapped file is rejected before
 * any plaintext is returned, and a forged header fails the Tink tag check.
 *
 * Channels are not thread-safe; use one per reader. Tink buffers one 1 MiB segment per channel.
 */
class VaultCrypto private constructor(private val aead: StreamingAead) {

    /**
     * Writes [header] to [dst], then returns a channel that encrypts into [dst].
     * Closing the returned channel writes the final segment and closes [dst].
     */
    fun newEncryptingChannel(dst: WritableByteChannel, header: FileHeader): WritableByteChannel {
        val headerBytes = header.encode()
        val buf = ByteBuffer.wrap(headerBytes)
        while (buf.hasRemaining()) dst.write(buf)
        return aead.newEncryptingChannel(dst, headerBytes)
    }

    /**
     * Seekable plaintext view of [src] (a whole vault file, read from position 0). `size()` is the
     * plaintext length and works before the first read. Closing it closes [src].
     */
    fun newSeekableDecryptingChannel(src: SeekableByteChannel, expected: FileHeader): SeekableByteChannel {
        src.position(0)
        val aad = checkHeader(FileHeader.readBytes(src), expected)
        val ciphertext = OffsetSeekableChannel(src, FileHeader.SIZE.toLong())
        return EofVerifyingChannel(aead.newSeekableDecryptingChannel(ciphertext, aad), ciphertext, aead, aad)
    }

    /** Sequential plaintext stream of [src] (a whole vault file). Closing it closes [src]. */
    fun newDecryptingStream(src: InputStream, expected: FileHeader): InputStream {
        val aad = checkHeader(FileHeader.readBytes(src), expected)
        return aead.newDecryptingStream(src, aad)
    }

    private fun checkHeader(actual: ByteArray, expected: FileHeader): ByteArray {
        val parsed = FileHeader.decode(actual)
        if (parsed != expected) throw VaultFormatException("file header does not match the expected file")
        return actual
    }

    companion object {
        /** Tink ciphertext segment size (1 MiB, includes the 16-byte tag). */
        const val CIPHERTEXT_SEGMENT_SIZE = 1 shl 20
        const val TAG_SIZE = 16

        /** Tink stream header: length byte + 32-byte HKDF salt + 7-byte nonce prefix. */
        const val TINK_HEADER_SIZE = 40

        /** Plaintext bytes in segments 1..n. */
        const val PLAINTEXT_SEGMENT_SIZE = CIPHERTEXT_SEGMENT_SIZE - TAG_SIZE

        /** Plaintext bytes in segment 0, which also holds the Tink header. */
        const val FIRST_SEGMENT_PLAINTEXT_SIZE = PLAINTEXT_SEGMENT_SIZE - TINK_HEADER_SIZE

        private val params = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB

        /** A fresh DK: one `AES256_GCM_HKDF_1MB` key. */
        fun newDataKeyset(): KeysetHandle {
            TinkRegistration.ensure()
            return KeysetHandle.generateNew(params)
        }

        /** Throws [GeneralSecurityException] unless [dk] is exactly one enabled `AES256_GCM_HKDF_1MB` key. */
        fun fromKeyset(dk: KeysetHandle): VaultCrypto = VaultCrypto(AesGcmHkdfStreaming.create(requireDataKey(dk)))

        internal fun requireDataKey(dk: KeysetHandle): AesGcmHkdfStreamingKey {
            if (dk.size() != 1) throw GeneralSecurityException("data keyset must hold exactly one key")
            val entry = dk.primary
            val key = entry.key
            if (entry.status != KeyStatus.ENABLED || key !is AesGcmHkdfStreamingKey || key.parameters != params) {
                throw GeneralSecurityException("data keyset is not one enabled AES256_GCM_HKDF_1MB key")
            }
            return key
        }

        /** Size on disk of a vault file holding [plaintextSize] bytes (header + Tink overhead). */
        fun fileSizeFor(plaintextSize: Long): Long {
            require(plaintextSize >= 0) { "size must be >= 0" }
            val afterFirst = maxOf(plaintextSize - FIRST_SEGMENT_PLAINTEXT_SIZE, 0L)
            val segments = 1L + (afterFirst + PLAINTEXT_SEGMENT_SIZE - 1) / PLAINTEXT_SEGMENT_SIZE
            return FileHeader.SIZE + TINK_HEADER_SIZE + plaintextSize + segments * TAG_SIZE
        }
    }
}

/**
 * Fixes end-of-file on Tink's seekable channel. Tink returns 0 instead of -1 at EOF unless the last
 * segment happens to be loaded (always 0 for an empty plaintext), and its `size()` is not authenticated.
 * Before the first -1, this authenticates the final segment, so a file truncated at a segment
 * boundary fails instead of reading as a shorter file.
 */
private class EofVerifyingChannel(
    private val inner: SeekableByteChannel,
    private val ciphertext: SeekableByteChannel,
    private val aead: StreamingAead,
    private val aad: ByteArray,
) : SeekableByteChannel by inner {
    private var endVerified = false

    // Delegation would return Tink's channel here, so `ch.position(p).read(buf)` would skip the EOF check.
    override fun position(newPosition: Long): SeekableByteChannel {
        inner.position(newPosition)
        return this
    }

    override fun truncate(size: Long): SeekableByteChannel {
        inner.truncate(size)
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        val pos = inner.position()
        val size = inner.size()
        return when {
            !dst.hasRemaining() -> 0
            pos < size -> inner.read(dst)
            else -> {
                if (!endVerified) {
                    verifyEnd(pos, size)
                    endVerified = true
                }
                -1
            }
        }
    }

    private fun verifyEnd(pos: Long, size: Long) {
        if (size > 0) {
            // Reading the last byte decrypts the last segment with the "final segment" flag.
            inner.position(size - 1)
            if (inner.read(ByteBuffer.allocate(1)) != 1) throw IOException("could not read the last segment")
            inner.position(pos)
        } else {
            // Empty plaintext: one tag-only segment. Tink's seekable channel never loads it; the stream does.
            ciphertext.position(0)
            val stream = aead.newDecryptingStream(Channels.newInputStream(ciphertext), aad)
            if (stream.read() != -1) throw IOException("unexpected plaintext in an empty file")
        }
    }
}

/** Registers Tink's streaming AEAD key creation and proto serialization once per process. */
internal object TinkRegistration {
    init {
        StreamingAeadConfig.register()
    }

    fun ensure() = Unit
}
