package com.qualifiedopinion.privatevault.format

import java.io.IOException

/** A vault file is malformed, truncated, of an unknown version, or not the file the caller expected. */
class VaultFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Throws [VaultFormatException] with [message] unless [condition] holds. */
internal inline fun formatCheck(condition: Boolean, message: () -> String) {
    if (!condition) throw VaultFormatException(message())
}
