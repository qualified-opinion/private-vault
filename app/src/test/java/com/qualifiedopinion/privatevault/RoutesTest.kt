package com.qualifiedopinion.privatevault

import com.qualifiedopinion.privatevault.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun startRouteIsHome() {
        assertEquals("home", Routes.HOME)
    }
}
