package com.example.helmet.service.runtime

import com.example.helmet.core.model.RuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareModePolicyTest {
    @Test
    fun releaseRejectsAndRevisionsStoredSimulationMode() {
        val stored = RuntimeConfig(revision = 7, simulatorEnabled = true)

        val effective = enforceHardwareModePolicy(stored, simulatedHardwareAllowed = false)

        assertFalse(effective.simulatorEnabled)
        assertEquals(8, effective.revision)
    }

    @Test
    fun debugKeepsExplicitSimulationMode() {
        val stored = RuntimeConfig(revision = 7, simulatorEnabled = true)

        val effective = enforceHardwareModePolicy(stored, simulatedHardwareAllowed = true)

        assertSame(stored, effective)
        assertTrue(effective.simulatorEnabled)
    }

    @Test
    fun realHardwareConfigurationIsNotRewritten() {
        val stored = RuntimeConfig(revision = 7, simulatorEnabled = false)

        val effective = enforceHardwareModePolicy(stored, simulatedHardwareAllowed = false)

        assertSame(stored, effective)
    }
}
