package com.qualifiedopinion.privatevault.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.qualifiedopinion.privatevault.vault.ItemKind
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale

/**
 * A photo or video picked with the system photo picker (or the document picker it falls back to).
 * The picker grants read access to [uri] only; nothing here needs a permission.
 */
class UriImportSource(context: Context, private val uri: Uri) : ImportSource {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    override fun describe(): SourceInfo {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1).takeIf { it >= 0 }
            }
        }
        val mime = resolver.getType(uri)?.lowercase(Locale.ROOT) ?: UNKNOWN_MIME
        return SourceInfo(name ?: DEFAULT_NAME, mime, size)
    }

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("no stream")

    override fun probe(info: SourceInfo): MediaDetails =
        if (ImportRules.kindOf(info.mime) == ItemKind.VIDEO) {
            MediaProbe.video(context, uri)
        } else {
            MediaProbe.photo(context, uri)
        }

    override fun toString(): String = "UriImportSource"

    private companion object {
        const val UNKNOWN_MIME = "application/octet-stream"
        const val DEFAULT_NAME = "unnamed"
    }
}
