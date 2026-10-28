package com.qualifiedopinion.privatevault.importer

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Date parsing for [MediaProbe], kept free of Android classes so it runs in JVM tests. */
internal object MediaDates {
    private const val VIDEO_DATE_LENGTH = 15 // "yyyyMMddTHHmmss"

    /** EXIF `DateTimeOriginal` ("yyyy:MM:dd HH:mm:ss", the camera's local time), read in [zone]. */
    fun exif(value: String, zone: TimeZone = TimeZone.getDefault()): Long? =
        parse(value.trim(), "yyyy:MM:dd HH:mm:ss", zone)

    /** Video `DATE` metadata ("yyyyMMdd'T'HHmmss.SSS'Z'", UTC). Cameras with no clock write 1904: dropped. */
    fun video(value: String): Long? =
        parse(value.trim().take(VIDEO_DATE_LENGTH), "yyyyMMdd'T'HHmmss", TimeZone.getTimeZone("UTC"))

    private fun parse(value: String, pattern: String, zone: TimeZone): Long? = try {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = zone
            isLenient = false
        }
        format.parse(value)?.time?.takeIf { it > 0 }
    } catch (@Suppress("SwallowedException") e: ParseException) {
        null // unknown date: the item is still imported
    }
}
