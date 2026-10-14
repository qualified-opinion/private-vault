package com.qualifiedopinion.privatevault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsTest {
    @Test
    fun acceptsSixToTwelveDigits() {
        assertTrue(Pins.isValid("123456".toByteArray()))
        assertTrue(Pins.isValid("123456789012".toByteArray()))
        assertFalse(Pins.isValid("12345".toByteArray()))
        assertFalse(Pins.isValid("1234567890123".toByteArray()))
        assertFalse(Pins.isValid("12345x".toByteArray()))
        assertFalse(Pins.isValid(ByteArray(0)))
    }

    @Test
    fun charsToAsciiBytes() {
        assertArrayEquals("0123456789".toByteArray(), Pins.toBytes("0123456789".toCharArray()))
        // Non-ASCII digits (e.g. Arabic-Indic) must not silently map to ASCII digits.
        assertFalse(Pins.isValid(Pins.toBytes("١٢٣٤٥٦".toCharArray())))
    }
}
