package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.FileType
import com.qualifiedopinion.privatevault.format.VaultFormatException
import com.qualifiedopinion.privatevault.format.toHex
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.ITEMS
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.STAGING
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.dataName
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.metaName
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.partName
import com.qualifiedopinion.privatevault.vault.VaultFiles.Companion.thumbName
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** What the importer knows about a new item. Id, size, hash and import time are filled in by [ItemRepository]. */
data class NewItem(
    val kind: ItemKind,
    val mime: String,
    val name: String,
    val w: Int? = null,
    val h: Int? = null,
    val rot: Int? = null,
    val durMs: Long? = null,
    val takenAt: Long? = null,
) {
    override fun toString(): String = "NewItem(kind=$kind)"
}

/** Result of [ItemRepository.loadAll]. [skipped] counts orphaned or unreadable entries (not shown). */
class LoadResult(val items: List<ItemMeta>, val skipped: Int)

/**
 * Items in the vault folder (planning pack §3.1): `items/<id>.vdat`, `.vthm` and `.vmeta`, all
 * encrypted with the session's DK and AAD = the 32-byte header (type + item id).
 *
 * - An item exists once its `.vmeta` exists: [create] writes it last, and [delete] removes it first.
 * - [create] streams the data through `staging/<id>.vdat.part` and moves it into `items/` only
 *   when it is complete and synced; plaintext is never written anywhere.
 * - Read-only folders allow reads only ([VaultReadOnlyException]).
 *
 * All calls block on I/O: run them off the main thread.
 */
class ItemRepository(
    private val files: VaultFiles,
    private val session: VaultSession,
    private val index: VaultIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val storage get() = files.storage

    /**
     * Encrypts [data] (read to its end, not closed) and [thumbnail] into a new item and adds it to the index.
     * On any failure the partial files are removed (best effort) and the error is rethrown.
     */
    fun create(info: NewItem, data: InputStream, thumbnail: ByteArray): ItemMeta {
        files.requireWritable()
        val crypto = session.crypto()
        val id = FileHeader.newId()
        val idHex = id.toHex()
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = storage.create(STAGING, partName(idHex)).let { ch ->
                encryptStream(crypto, ch, FileHeader(FileType.DATA, id), data, digest)
            }
            storage.move(STAGING, partName(idHex), ITEMS)
            storage.rename(ITEMS, partName(idHex), dataName(idHex))
            writeSmall(crypto, thumbName(idHex), FileHeader(FileType.THUMB, id), thumbnail)
            val meta = ItemMeta(
                id = idHex,
                kind = info.kind,
                mime = info.mime,
                name = info.name,
                size = size,
                sha256 = digest.digest().toHex(),
                w = info.w,
                h = info.h,
                rot = info.rot,
                durMs = info.durMs,
                takenAt = info.takenAt,
                importedAt = clock(),
            )
            writeSmall(crypto, metaName(idHex), FileHeader(FileType.META, id), ItemMetaCodec.encode(meta))
            committed = true
            index.add(meta)
            return meta
        } finally {
            if (!committed) removeQuietly(idHex)
        }
    }

    /**
     * Lists `items/` and decrypts every `.vmeta` into the index (planning pack §2: rebuilt on unlock).
     * Entries without a `.vdat`, with a corrupt or tampered `.vmeta`, or with a stray name are skipped.
     * [VaultUnavailableException] propagates: the folder is gone, not the item.
     */
    fun loadAll(): LoadResult {
        val crypto = session.crypto()
        val names = storage.list(ITEMS).toHashSet()
        val items = ArrayList<ItemMeta>()
        var skipped = 0
        for (name in names) {
            if (!name.endsWith(VaultFiles.META_SUFFIX)) continue
            val idHex = name.removeSuffix(VaultFiles.META_SUFFIX)
            val meta = if (VaultFiles.isItemId(idHex) && dataName(idHex) in names) readMeta(crypto, idHex) else null
            if (meta != null) items += meta else skipped++
        }
        index.replaceAll(items)
        return LoadResult(index.items.value, skipped)
    }

    /** Deletes the item's three files (`.vmeta` first, so a partial delete never shows a broken item). */
    fun delete(idHex: String) {
        files.requireWritable()
        require(VaultFiles.isItemId(idHex)) { "not an item id" }
        storage.delete(ITEMS, metaName(idHex))
        index.remove(idHex)
        storage.delete(ITEMS, dataName(idHex))
        storage.delete(ITEMS, thumbName(idHex))
    }

    /**
     * A seekable plaintext view of the item's data (for the player in PR6). The channel is tracked by
     * the session and closed on lock; closing it closes the file.
     */
    fun openData(idHex: String): SeekableByteChannel {
        val crypto = session.crypto()
        val header = FileHeader(FileType.DATA, idFromHex(idHex))
        val plain = storage.openRead(ITEMS, dataName(idHex)).closeOnFailure { raw ->
            crypto.newSeekableDecryptingChannel(raw, header)
        }
        return session.track(TrackedChannel(plain, session))
    }

    /** The decrypted thumbnail bytes (at most [MAX_THUMBNAIL_SIZE]). */
    fun readThumbnail(idHex: String): ByteArray =
        readSmall(session.crypto(), thumbName(idHex), FileHeader(FileType.THUMB, idFromHex(idHex)), MAX_THUMBNAIL_SIZE)

    private fun readMeta(crypto: VaultCrypto, idHex: String): ItemMeta? = try {
        val header = FileHeader(FileType.META, idFromHex(idHex))
        val bytes = readSmall(crypto, metaName(idHex), header, ItemMetaCodec.MAX_SIZE)
        ItemMetaCodec.decode(bytes, idHex)
    } catch (e: VaultUnavailableException) {
        throw e
    } catch (@Suppress("SwallowedException") e: IOException) {
        null // corrupt, truncated or tampered (Tink reports a bad tag as IOException)
    } catch (@Suppress("SwallowedException") e: GeneralSecurityException) {
        null
    }

    private fun readSmall(crypto: VaultCrypto, name: String, header: FileHeader, maxSize: Int): ByteArray {
        val stream = storage.openRead(ITEMS, name).closeOnFailure { raw ->
            crypto.newDecryptingStream(Channels.newInputStream(raw), header)
        }
        stream.use { input ->
            val out = ByteArray(maxSize + 1)
            var n = 0
            while (n < out.size) {
                val r = input.read(out, n, out.size - n)
                if (r < 0) break
                n += r
            }
            if (n > maxSize) throw VaultFormatException("item file too large")
            return out.copyOf(n)
        }
    }

    private fun writeSmall(crypto: VaultCrypto, name: String, header: FileHeader, plaintext: ByteArray) {
        val enc = storage.create(ITEMS, name).closeOnFailure { ch ->
            crypto.newEncryptingChannel(SyncOnClose(ch), header)
        }
        enc.use {
            val buf = ByteBuffer.wrap(plaintext)
            while (buf.hasRemaining()) it.write(buf)
        }
    }

    /** Streams [data] into [dst] encrypted; returns the plaintext size. Closes [dst] after an fsync. */
    private fun encryptStream(
        crypto: VaultCrypto,
        dst: FileChannel,
        header: FileHeader,
        data: InputStream,
        digest: MessageDigest,
    ): Long {
        var size = 0L
        val channel = dst.closeOnFailure { crypto.newEncryptingChannel(SyncOnClose(it), header) }
        channel.use { enc ->
            val chunk = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val n = data.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
                val buf = ByteBuffer.wrap(chunk, 0, n)
                while (buf.hasRemaining()) enc.write(buf)
                size += n
            }
        }
        return size
    }

    private fun removeQuietly(idHex: String) {
        val leftovers = listOf(
            STAGING to partName(idHex),
            ITEMS to partName(idHex),
            ITEMS to metaName(idHex),
            ITEMS to dataName(idHex),
            ITEMS to thumbName(idHex),
        )
        for ((dir, name) in leftovers) {
            try {
                storage.delete(dir, name)
            } catch (@Suppress("SwallowedException") e: IOException) {
                // Folder gone or read-only: startup cleanup and loadAll() ignore what is left.
            }
        }
    }

    companion object {
        /** Planning pack §6 PR4: 256 KiB copy buffer. */
        const val COPY_BUFFER_SIZE = 256 * 1024
        const val MAX_THUMBNAIL_SIZE = 1024 * 1024

        fun idFromHex(idHex: String): ByteArray {
            require(VaultFiles.isItemId(idHex)) { "not an item id" }
            return ByteArray(FileHeader.ID_SIZE) { i -> idHex.substring(2 * i, 2 * i + 2).toInt(HEX_RADIX).toByte() }
        }

        private const val HEX_RADIX = 16
    }
}

/** Runs [block] with this; if it throws, closes this (suppressing secondary errors) and rethrows. */
internal inline fun <C : Closeable, R> C.closeOnFailure(block: (C) -> R): R =
    try {
        block(this)
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        try {
            close()
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            t.addSuppressed(e)
        }
        throw t
    }

/** Writes through to [ch]; on close, fsyncs and closes it (Tink's close writes the final segment first). */
private class SyncOnClose(private val ch: FileChannel) : WritableByteChannel {
    override fun write(src: ByteBuffer): Int = ch.write(src)

    override fun isOpen(): Boolean = ch.isOpen

    override fun close() {
        ch.use { it.force(true) }
    }
}

/** Untracks itself from the session when closed by the caller. */
private class TrackedChannel(
    private val inner: SeekableByteChannel,
    private val session: VaultSession,
) : SeekableByteChannel by inner {
    // Delegation would return the inner channel, so `ch.position(p).close()` would skip untracking.
    override fun position(newPosition: Long): SeekableByteChannel {
        inner.position(newPosition)
        return this
    }

    override fun truncate(size: Long): SeekableByteChannel {
        inner.truncate(size)
        return this
    }

    override fun close() {
        session.untrack(this)
        inner.close()
    }
}
