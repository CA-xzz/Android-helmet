package com.example.helmet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantDebugUiPolicyTest {
    @Test
    fun debugBuildIncludesClearlySeparatedDebugPage() {
        assertTrue(VariantDebugUiPolicy.debugToolsAvailable)
        assertEquals(DashboardPage.DEBUG, dashboardPages(VariantDebugUiPolicy.debugToolsAvailable).last())
    }
}
