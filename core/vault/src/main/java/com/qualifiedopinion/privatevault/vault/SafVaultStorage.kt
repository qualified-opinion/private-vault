package com.qualifiedopinion.privatevault.vault

import android.net.Uri
import android.provider.DocumentsContract
import com.qualifiedopinion.privatevault.vault.VaultStorage.Companion.ROOT
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException

/**
 * [VaultStorage] over a persisted SAF tree (planning pack §5).
 *
 * - Document ids are cached per directory from one listing query. A lookup that misses re-lists that
 *   directory once. Our own create/rename/move/delete keep the cache current.
 * - [preCheck] runs before every provider call. [VolumeMonitor] makes it check the grant and the
 *   volume (§5.4: "before every open").
 * - Any `IOException`, `SecurityException` or other runtime error the provider throws is reported to
 *   [onProviderFailure] and rethrown as [VaultUnavailableException] (§5.4: "vault gone").
 *   Our own checks (name clash, missing file in a fresh listing) are not provider failures.
 * - [readOnly] is asked before every write.
 * - [rootDocumentId] defaults to the tree's own root. A descendant folder can be used instead (the
 *   benchmark's scratch vault); every URI is still built from [treeUri], so no other grant is needed.
 */
@Suppress("TooManyFunctions") // the VaultStorage interface plus cache helpers
class SafVaultStorage(
    val treeUri: Uri,
    private val io: SafIo,
    private val readOnly: () -> Boolean,
    private val preCheck: () -> Unit = {},
    private val onProviderFailure: (Throwable) -> Unit = {},
    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : VaultStorage {
    private val dirIds = HashMap<String, String>().apply { put(ROOT, rootDocumentId) }
    private val children = HashMap<String, MutableMap<String, SafIo.Entry>>()
    private val guard = Any()

    override val isReadOnly: Boolean get() = readOnly()

    override fun list(dir: String): List<String> =
        refresh(dir).values.filterNot { it.isDirectory }.map { it.name }.sorted()

    override fun exists(dir: String, name: String): Boolean = lookup(dir, name)?.isDirectory == false

    override fun ensureDir(dir: String) {
        requireWritable()
        checkName(dir)
        val existing = lookup(ROOT, dir)
        if (existing != null) {
            if (!existing.isDirectory) throw IOException("a file is in the way of a vault directory")
            return
        }
        val created = provider {
            io.createDocument(treeUri, uriOf(rootDocumentId), DocumentsContract.Document.MIME_TYPE_DIR, dir)
        }
        expectName(created, dir, deleteOnMismatch = true)
        cache(ROOT, created)
    }

    override fun openRead(dir: String, name: String): FileChannel {
        val entry = lookup(dir, name) ?: throw FileNotFoundException("no such file")
        return provider { io.openRead(uriOf(entry.documentId)) }
    }

    override fun create(dir: String, name: String): FileChannel {
        requireWritable()
        checkName(name)
        if (lookup(dir, name) != null) throw FileAlreadyExistsException(name)
        val parent = dirId(dir) ?: throw FileNotFoundException("no such directory")
        val created = provider { io.createDocument(treeUri, uriOf(parent), SafIo.MIME_BINARY, name) }
        expectName(created, name, deleteOnMismatch = true)
        cache(dir, created)
        return provider { io.openWrite(uriOf(created.documentId)) }
    }

    override fun rename(dir: String, from: String, to: String) {
        requireWritable()
        checkName(to)
        val entry = lookup(dir, from) ?: throw FileNotFoundException("no such file")
        if (lookup(dir, to) != null) throw FileAlreadyExistsException(to)
        val renamed = provider { io.rename(treeUri, uriOf(entry.documentId), to) }
        uncache(dir, from)
        cache(dir, renamed)
        expectName(renamed, to, deleteOnMismatch = false)
    }

    override fun move(fromDir: String, name: String, toDir: String) {
        requireWritable()
        val entry = lookup(fromDir, name) ?: throw FileNotFoundException("no such file")
        if (lookup(toDir, name) != null) throw FileAlreadyExistsException(name)
        val from = dirId(fromDir) ?: throw FileNotFoundException("no such directory")
        val to = dirId(toDir) ?: throw FileNotFoundException("no such directory")
        val moved = provider { io.move(treeUri, uriOf(entry.documentId), uriOf(from), uriOf(to)) }
        uncache(fromDir, name)
        cache(toDir, moved)
        expectName(moved, name, deleteOnMismatch = false)
    }

    override fun delete(dir: String, name: String): Boolean {
        requireWritable()
        val entry = lookup(dir, name) ?: return false
        if (entry.isDirectory) throw IOException("not a file")
        val deleted = provider { io.delete(uriOf(entry.documentId)) }
        uncache(dir, name)
        return deleted
    }

    /** Free bytes as the provider reports them for the tree's root (`primary`, the SD card's UUID, …). */
    override fun freeBytes(): Long? {
        val authority = treeUri.authority ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        return provider { io.availableBytes(authority, rootId) }
    }

    /** Flags of the tree root (e.g. `FLAG_DIR_SUPPORTS_CREATE`, absent on a read-only mount). */
    fun rootFlags(): Int = provider { io.query(treeUri, uriOf(rootDocumentId)).flags }

    private fun requireWritable() {
        preCheck() // refresh mount/provider flags before deciding whether this operation may write
        if (readOnly()) throw VaultReadOnlyException()
    }

    /**
     * The provider used another name (a clash we didn't see in a stale listing): fail, never continue
     * under another name. A just-created empty file is removed; renamed or moved data is left alone,
     * since its odd name keeps it out of every vault listing.
     */
    private fun expectName(entry: SafIo.Entry, wanted: String, deleteOnMismatch: Boolean) {
        if (entry.name == wanted) return
        if (deleteOnMismatch) deleteQuietly(entry)
        throw FileAlreadyExistsException(wanted, null, "the provider used a different name")
    }

    private fun deleteQuietly(entry: SafIo.Entry) {
        try {
            provider { io.delete(uriOf(entry.documentId)) }
        } catch (@Suppress("SwallowedException") e: IOException) {
            // Reported by provider {}; the caller's clash error is the one that matters.
        }
    }

    private fun lookup(dir: String, name: String): SafIo.Entry? {
        checkName(name)
        synchronized(guard) { children[dir]?.get(name)?.let { return it } }
        return refresh(dir)[name]
    }

    private fun dirId(dir: String): String? {
        synchronized(guard) { dirIds[dir]?.let { return it } }
        return lookup(ROOT, checkName(dir))?.takeIf { it.isDirectory }?.documentId
    }

    /** Re-lists [dir] and replaces its cache. A missing directory lists as empty. */
    private fun refresh(dir: String): Map<String, SafIo.Entry> {
        val id = if (dir == ROOT) rootDocumentId else dirId(dir) ?: return emptyMap()
        val entries = provider { io.list(treeUri, id) }
        val map = entries.associateByTo(HashMap()) { it.name }
        synchronized(guard) {
            children[dir] = map
            if (dir == ROOT) {
                dirIds.keys.retainAll(setOf(ROOT))
                for (e in entries) if (e.isDirectory) dirIds[e.name] = e.documentId
            }
        }
        return map
    }

    private fun cache(dir: String, entry: SafIo.Entry) {
        synchronized(guard) {
            children.getOrPut(dir) { HashMap() }[entry.name] = entry
            if (dir == ROOT && entry.isDirectory) dirIds[entry.name] = entry.documentId
        }
    }

    private fun uncache(dir: String, name: String) {
        synchronized(guard) {
            children[dir]?.remove(name)
            if (dir == ROOT) dirIds.remove(name)
        }
    }

    private fun uriOf(documentId: String): Uri = io.documentUri(treeUri, documentId)

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private inline fun <T> provider(block: () -> T): T {
        preCheck()
        try {
            return block()
        } catch (e: IOException) {
            throw gone(e)
        } catch (e: SecurityException) {
            throw gone(e)
        } catch (e: IllegalArgumentException) {
            throw gone(e) // ExternalStorageProvider reports a vanished root this way
        } catch (e: IllegalStateException) {
            throw gone(e)
        } catch (e: UnsupportedOperationException) {
            throw gone(e)
        }
    }

    private fun gone(e: Throwable): VaultUnavailableException {
        onProviderFailure(e)
        return e as? VaultUnavailableException ?: VaultUnavailableException("vault folder not available", e)
    }
}
