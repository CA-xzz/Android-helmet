package com.example.helmet

import org.junit.Assert.assertFalse
import org.junit.Test

class VariantDebugUiPolicyTest {
    @Test
    fun releaseBuildDoesNotContainDebugPage() {
        assertFalse(VariantDebugUiPolicy.debugToolsAvailable)
        assertFalse(dashboardPages(VariantDebugUiPolicy.debugToolsAvailable).contains(DashboardPage.DEBUG))
    }
}
