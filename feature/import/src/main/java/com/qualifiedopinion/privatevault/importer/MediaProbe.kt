package com.qualifiedopinion.privatevault.importer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.ByteArrayOutputStream

/**
 * Best-effort metadata and a small JPEG thumbnail for a picked photo or video. Everything stays in
 * memory: nothing is written to disk in plaintext. A failure gives null fields or an empty
 * thumbnail, and the item is still imported.
 */
internal object MediaProbe {
    /** Longest edge of a thumbnail, in pixels. */
    const val THUMB_EDGE = 320
    private const val JPEG_QUALITY = 85

    fun photo(context: Context, uri: Uri): MediaDetails {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        quietly { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val exif = quietly { resolver.openInputStream(uri)?.use { ExifInterface(it) } }
        val rot = exif?.let(::exifRotation)
        val bitmap = systemThumbnail(resolver, uri) ?: quietly { decodeSampled(resolver, uri, bounds, rot ?: 0) }
        return MediaDetails(
            w = bounds.outWidth.takeIf { it > 0 },
            h = bounds.outHeight.takeIf { it > 0 },
            rot = rot,
            takenAt = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { MediaDates.exif(it) },
            thumbnail = bitmap?.let(::toJpeg) ?: ByteArray(0),
        )
    }

    fun video(context: Context, uri: Uri): MediaDetails {
        val retriever = MediaMetadataRetriever()
        return try {
            val opened = quietly { retriever.setDataSource(context, uri) } != null
            fun meta(key: Int): String? = if (opened) quietly { retriever.extractMetadata(key) } else null
            val bitmap = systemThumbnail(context.contentResolver, uri)
                ?: if (opened) quietly { videoFrame(retriever) } else null
            MediaDetails(
                w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
                durMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                takenAt = meta(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { MediaDates.video(it) },
                thumbnail = bitmap?.let(::toJpeg) ?: ByteArray(0),
            )
        } finally {
            quietly { retriever.release() }
        }
    }

    /** API 29+: the system's own thumbnail (handles HEIC, rotation and video frames). Null if it has none. */
    private fun systemThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            quietly { resolver.loadThumbnail(uri, Size(THUMB_EDGE, THUMB_EDGE), null) }?.let { scaleAndRotate(it, 0) }
        } else {
            null
        }

    /** Decodes a downsampled copy (never the full image: bounds must be known). */
    private fun decodeSampled(resolver: ContentResolver, uri: Uri, bounds: BitmapFactory.Options, rot: Int): Bitmap? {
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= THUMB_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return decoded?.let { scaleAndRotate(it, rot) }
    }

    private fun videoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_EDGE, THUMB_EDGE)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        return frame?.let { scaleAndRotate(it, 0) }
    }

    /** Shrinks so the longest edge is at most [THUMB_EDGE], and applies the EXIF rotation. */
    private fun scaleAndRotate(src: Bitmap, rot: Int): Bitmap {
        val scale = minOf(1f, THUMB_EDGE.toFloat() / maxOf(src.width, src.height, 1))
        if (scale >= 1f && rot == 0) return src
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rot.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Suppress("MagicNumber") // degrees
    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }

    /**
     * Runs a decoder call and turns any failure into null. Decoders throw IOException,
     * IllegalArgumentException, IllegalStateException and plain RuntimeException ("setDataSource
     * failed"); a probe must never fail the import.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
