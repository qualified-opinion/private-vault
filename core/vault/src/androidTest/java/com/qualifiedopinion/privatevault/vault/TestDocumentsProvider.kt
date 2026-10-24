package com.qualifiedopinion.privatevault.vault

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A minimal DocumentsProvider over a temp dir, so SAF code paths run in CI without a picker.
 * It copies ExternalStorageProvider's behaviour where it matters to us:
 * - create, rename and move on a name clash pick `name (1)` instead of failing;
 * - the root drops `FLAG_DIR_SUPPORTS_CREATE` when [readOnly] (a read-only mount);
 * - [failAll] makes every call throw `FileNotFoundException`, like a removed card.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private val base: File get() = File(requireNotNull(context).cacheDir, "test-docs").apply { mkdirs() }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        check()
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "test")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        check()
        return MatrixCursor(projection ?: DOC_COLUMNS).apply { addRow(this, fileOf(documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        check()
        val dir = fileOf(parentDocumentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            dir.listFiles()?.sortedBy { it.name }?.forEach { addRow(this, it) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check()
        opened++
        return ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check()
        val target = unique(fileOf(parentDocumentId), displayName)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw IOException("create failed")
        return idOf(target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        check()
        val file = fileOf(documentId)
        val target = unique(requireNotNull(file.parentFile), displayName)
        if (!file.renameTo(target)) throw IOException("rename failed")
        return idOf(target)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        check()
        val file = fileOf(sourceDocumentId)
        val target = unique(fileOf(targetParentDocumentId), file.name)
        if (!file.renameTo(target)) throw IOException("move failed")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        check()
        if (!fileOf(documentId).deleteRecursively()) throw IOException("delete failed")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    private fun addRow(cursor: MatrixCursor, file: File) {
        val dirFlags = if (readOnly) 0 else Document.FLAG_DIR_SUPPORTS_CREATE
        val fileFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == base) "root" else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_FLAGS, if (file.isDirectory) dirFlags else fileFlags)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    /** ExternalStorageProvider-style unique name: `name (1).ext`. */
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        while (f.exists()) {
            f = File(dir, name.substring(0, dot) + " (" + n++ + ")" + name.substring(dot))
        }
        return f
    }

    private fun fileOf(documentId: String): File {
        if (documentId != ROOT_ID && !documentId.startsWith("$ROOT_ID/")) throw FileNotFoundException("bad id")
        val rel = documentId.removePrefix(ROOT_ID).trimStart('/')
        val f = if (rel.isEmpty()) base else File(base, rel)
        if (!f.exists()) throw FileNotFoundException("missing")
        return f
    }

    private fun idOf(file: File): String = if (file == base) ROOT_ID else ROOT_ID + "/" + file.relativeTo(base).path

    private fun check() {
        if (failAll) throw FileNotFoundException("simulated: card removed")
    }

    companion object {
        const val AUTHORITY = "com.qualifiedopinion.privatevault.vault.test.documents"
        const val ROOT_ID = "root"

        @Volatile
        var readOnly = false

        @Volatile
        var failAll = false

        @Volatile
        var opened = 0

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED,
        )

        fun baseDir(context: android.content.Context) = File(context.cacheDir, "test-docs")
    }
}
