package com.qualifiedopinion.privatevault.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModuleTest {
    @Test
    fun moduleNameIsWired() {
        assertEquals("core:media", MediaModule.MODULE)
    }
}
