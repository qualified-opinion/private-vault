package com.qualifiedopinion.privatevault.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportModuleTest {
    @Test
    fun moduleNameIsWired() {
        assertEquals("feature:import", ImportModule.MODULE)
    }
}
