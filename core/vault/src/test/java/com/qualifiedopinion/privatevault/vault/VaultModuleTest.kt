package com.qualifiedopinion.privatevault.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultModuleTest {
    @Test
    fun moduleNameIsWired() {
        assertEquals("core:vault", VaultModule.MODULE)
    }
}
