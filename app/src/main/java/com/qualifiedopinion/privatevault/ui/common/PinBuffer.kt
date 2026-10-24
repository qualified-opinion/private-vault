package com.qualifiedopinion.privatevault.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.qualifiedopinion.privatevault.crypto.Pins

/**
 * PIN digits as ASCII bytes in a fixed array (planning pack §4.3: never a `String`, no system
 * keyboard). Only the length is Compose state, so the digits never reach the snapshot system.
 */
class PinBuffer {
    private val digits = ByteArray(Pins.MAX_LENGTH)

    var length by mutableIntStateOf(0)
        private set

    val canSubmit: Boolean get() = length >= Pins.MIN_LENGTH

    fun push(digit: Int) {
        require(digit in 0..MAX_DIGIT) { "not a digit" }
        if (length < digits.size) {
            digits[length] = ('0'.code + digit).toByte()
            length++
        }
    }

    fun pop() {
        if (length > 0) {
            length--
            digits[length] = 0
        }
    }

    /** Hands out a copy (the receiver owns and wipes it) and wipes this buffer. */
    fun take(): ByteArray {
        val out = digits.copyOf(length)
        wipe()
        return out
    }

    fun wipe() {
        digits.fill(0)
        length = 0
    }

    private companion object {
        const val MAX_DIGIT = 9
    }
}
