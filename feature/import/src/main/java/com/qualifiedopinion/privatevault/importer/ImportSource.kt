package com.qualifiedopinion.privatevault.importer

import java.io.InputStream

/** One picked photo or video, as the importer needs it. The app's version is [UriImportSource]. */
interface ImportSource {
    /** Name, type and size, from the provider. Cheap: no file content is read. */
    fun describe(): SourceInfo

    /** Opens the original for reading. The caller closes it. */
    fun open(): InputStream

    /** Dimensions, rotation, duration, date taken and a small JPEG thumbnail. Best effort, never throws. */
    fun probe(info: SourceInfo): MediaDetails
}

/** [size] is null when the provider doesn't report it. [mime] is lower case. */
data class SourceInfo(val name: String, val mime: String, val size: Long?) {
    override fun toString(): String = "SourceInfo(mime=$mime, size=$size)"
}

/** What [ImportSource.probe] found. Unknown fields are null; an empty [thumbnail] means none could be made. */
class MediaDetails(
    val w: Int? = null,
    val h: Int? = null,
    val rot: Int? = null,
    val durMs: Long? = null,
    val takenAt: Long? = null,
    val thumbnail: ByteArray = ByteArray(0),
)
