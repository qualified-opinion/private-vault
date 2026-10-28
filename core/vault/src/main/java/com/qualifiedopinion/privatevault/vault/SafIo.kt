package com.qualifiedopinion.privatevault.vault

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Raw Storage Access Framework I/O (planning pack §5.2, §5.3).
 *
 * - [openRead]/[openWrite] return a `FileChannel` from the provider's `ParcelFileDescriptor`, so bulk
 *   I/O and seeks run on the file descriptor with no stream layer. The channel owns the PFD: closing
 *   the channel closes its `AutoClose*Stream`, which closes the PFD. If the channel can't be built,
 *   the PFD is closed before the error propagates. No PFD is ever left open.
 * - Writes open with mode `"rw"`, never `"w"` (some providers truncate on `"w"`, §5.3). Callers only
 *   write to files they just created, so nothing stale is left past the end.
 * - Everything else goes through `DocumentsContract`. Providers may give a different name than the
 *   one asked for (ExternalStorageProvider makes `x (1)` on a clash), so [createDocument] and
 *   [rename] return what the provider actually did, and callers check it.
 *
 * Errors are the provider's own (`FileNotFoundException`, `SecurityException`, …). Deciding that
 * they mean "vault gone" is [SafVaultStorage]'s job.
 */
class SafIo(private val resolver: ContentResolver) {

    /** One row of a document query. */
    class Entry(val documentId: String, val name: String, val mimeType: String, val size: Long, val flags: Int) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    fun openRead(uri: Uri): FileChannel {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(NO_DESCRIPTOR)
        return pfd.closeOnFailure { ParcelFileDescriptor.AutoCloseInputStream(it).channel }
    }

    /** Opens for writing at position 0 with mode `"rw"` (no truncation). */
    fun openWrite(uri: Uri): FileChannel {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException(NO_DESCRIPTOR)
        return pfd.closeOnFailure { ParcelFileDescriptor.AutoCloseOutputStream(it).channel }
    }

    /** Creates a document under [parent] (a tree document URI). Returns the new document's entry. */
    fun createDocument(treeUri: Uri, parent: Uri, mimeType: String, name: String): Entry {
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("provider did not create the document")
        return query(treeUri, uri)
    }

    /** Renames [uri]. Returns the renamed document's entry; its id may have changed. */
    fun rename(treeUri: Uri, uri: Uri, newName: String): Entry {
        val renamed = DocumentsContract.renameDocument(resolver, uri, newName) ?: uri // null: same document id
        return query(treeUri, renamed)
    }

    /** Moves [uri] from [fromParent] to [toParent] (API 24+, FLAG_SUPPORTS_MOVE). Returns the moved entry. */
    fun move(treeUri: Uri, uri: Uri, fromParent: Uri, toParent: Uri): Entry {
        val moved = DocumentsContract.moveDocument(resolver, uri, fromParent, toParent)
            ?: throw IOException("provider did not move the document")
        return query(treeUri, moved)
    }

    fun delete(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)

    /** The children of [parentDocumentId] in one cursor query (planning pack §5.1). */
    fun list(treeUri: Uri, parentDocumentId: String): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(children, PROJECTION, null, null, null)
            ?: throw IOException("provider returned no cursor")
        return cursor.use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) out += c.entry()
            out
        }
    }

    /** The entry for one document URI. */
    fun query(treeUri: Uri, uri: Uri): Entry {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(uri))
        val cursor = resolver.query(docUri, PROJECTION, null, null, null)
            ?: throw IOException("provider returned no cursor")
        return cursor.use { c ->
            if (!c.moveToFirst()) throw FileNotFoundException("document not found")
            c.entry()
        }
    }

    /** Free bytes on the provider root [rootId], or null if the provider doesn't report it. */
    fun availableBytes(authority: String, rootId: String): Long? {
        val cursor = resolver.query(DocumentsContract.buildRootsUri(authority), ROOT_PROJECTION, null, null, null)
            ?: return null
        return cursor.use { c ->
            var bytes: Long? = null
            while (bytes == null && c.moveToNext()) {
                if (c.getString(COL_ROOT_ID) == rootId && !c.isNull(COL_ROOT_BYTES)) bytes = c.getLong(COL_ROOT_BYTES)
            }
            bytes
        }
    }

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    companion object {
        const val MIME_BINARY = "application/octet-stream"
        private const val NO_DESCRIPTOR = "provider returned no descriptor"
        private const val COL_ID = 0
        private const val COL_NAME = 1
        private const val COL_MIME = 2
        private const val COL_SIZE = 3
        private const val COL_FLAGS = 4
        private const val COL_ROOT_ID = 0
        private const val COL_ROOT_BYTES = 1

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )

        /** Columns are in [PROJECTION] order. */
        private fun Cursor.entry(): Entry = Entry(
            documentId = getString(COL_ID),
            name = getString(COL_NAME).orEmpty(),
            mimeType = getString(COL_MIME).orEmpty(),
            size = if (isNull(COL_SIZE)) -1L else getLong(COL_SIZE),
            flags = if (isNull(COL_FLAGS)) 0 else getInt(COL_FLAGS),
        )
    }
}
