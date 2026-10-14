package com.qualifiedopinion.privatevault.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoModuleTest {
    @Test
    fun moduleNameIsWired() {
        assertEquals("core:crypto", CryptoModule.MODULE)
    }
}
