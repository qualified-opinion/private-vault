package com.qualifiedopinion.privatevault.crypto

/** PIN rules (planning pack §4.3). A PIN is only ever a `CharArray` or `ByteArray`, never a `String`. */
object Pins {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    /** 6–12 ASCII digits. */
    fun isValid(pin: ByteArray): Boolean =
        pin.size in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'.code.toByte()..'9'.code.toByte() }

    /** ASCII bytes of [pin]. The caller zeroizes both arrays. Non-ASCII chars become `?` (and fail [isValid]). */
    fun toBytes(pin: CharArray): ByteArray =
        ByteArray(pin.size) { i -> pin[i].code.let { if (it < ASCII_LIMIT) it.toByte() else '?'.code.toByte() } }

    private const val ASCII_LIMIT = 0x80
}
