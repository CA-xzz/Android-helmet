package com.example.helmet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeConfigTest {
    @Test
    fun simulatedHardwareRequiresExplicitOptIn() {
        assertFalse(RuntimeConfig().simulatorEnabled)
        assertEquals(RtkTransportMode.HSL, RuntimeConfig().rtk.transportMode)
        assertEquals("/dev/ttyAS4", RuntimeConfig().rtk.directDevicePath)
    }

    @Test
    fun directRtkUsesFixedPathAndWhitelistedBaudRates() {
        assertEquals(
            460_800,
            RtkRuntimeConfig(
                transportMode = RtkTransportMode.DIRECT_UART4,
                directBaudRate = 460_800,
            ).directBaudRate,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RtkRuntimeConfig(directDevicePath = "/dev/ttyAS5")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RtkRuntimeConfig(directBaudRate = 123_456)
        }
    }

    @Test
    fun optionalPersonBindingUsesBackendIdentifierFormat() {
        assertNull(RuntimeConfig().personId)
        assertEquals("worker-42:night", RuntimeConfig(personId = "worker-42:night").personId)
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig(personId = "worker 42") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig(personId = "x".repeat(129)) }
    }

    @Test
    fun safetyThresholdFingerprintIsDeterministicAndBindsContentNotOnlyVersion() {
        val original = SafetyThresholdConfig(version = 7)
        val same = original.copy()
        val changedWithoutVersionBump = original.copy(heightThresholdMillimetres = 3_000)

        assertEquals(original.canonicalFingerprint(), same.canonicalFingerprint())
        assertFalse(original.canonicalFingerprint() == changedWithoutVersionBump.canonicalFingerprint())
        assertEquals(64, original.canonicalFingerprint().length)
    }
}
