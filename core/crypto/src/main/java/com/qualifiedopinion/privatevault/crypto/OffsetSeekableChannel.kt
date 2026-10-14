package com.qualifiedopinion.privatevault.crypto

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Exposes [base] from byte [offset] onwards as a channel starting at position 0.
 *
 * Tink's seekable API expects the ciphertext at position 0, but vault files start with a 32-byte
 * [com.qualifiedopinion.privatevault.format.FileHeader] (planning pack §3.4). `position()` and
 * `size()` are shifted by [offset]; [close] closes [base].
 */
class OffsetSeekableChannel(private val base: SeekableByteChannel, private val offset: Long) : SeekableByteChannel {

    init {
        require(offset >= 0) { "offset must be >= 0" }
        if (base.position() < offset) base.position(offset)
    }

    override fun read(dst: ByteBuffer): Int = base.read(dst)

    override fun write(src: ByteBuffer): Int = base.write(src)

    override fun position(): Long = base.position() - offset

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0) { "position must be >= 0" }
        base.position(newPosition + offset)
        return this
    }

    override fun size(): Long = maxOf(base.size() - offset, 0L)

    override fun truncate(size: Long): SeekableByteChannel {
        require(size >= 0) { "size must be >= 0" }
        base.truncate(size + offset)
        return this
    }

    override fun isOpen(): Boolean = base.isOpen

    override fun close() = base.close()
}
