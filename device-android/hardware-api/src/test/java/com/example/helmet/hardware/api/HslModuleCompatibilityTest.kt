package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslModuleHello
import org.junit.Assert.assertEquals
import org.junit.Test

class HslModuleCompatibilityTest {
    @Test
    fun acceptsMatchingMajorAndSufficientMinorWithRequiredCapabilities() {
        val result = HslModuleContract.evaluate(
            hello().copy(
                contractMinor = HslModuleContract.version.minor + 1,
                capabilityMask = HslModuleContract.REQUIRED_BASE_CAPABILITIES,
            ),
        )

        assertEquals(HardwareCompatibility.COMPATIBLE, result.compatibility)
        assertEquals(0L, result.missingCapabilityMask)
    }

    @Test
    fun rejectsDifferentMajorOrOlderMinor() {
        assertEquals(
            HardwareCompatibility.CONTRACT_VERSION_MISMATCH,
            HslModuleContract.evaluate(
                hello().copy(contractMajor = HslModuleContract.version.major + 1),
            ).compatibility,
        )
        if (HslModuleContract.version.minor > 0) {
            assertEquals(
                HardwareCompatibility.CONTRACT_VERSION_MISMATCH,
                HslModuleContract.evaluate(
                    hello().copy(contractMinor = HslModuleContract.version.minor - 1),
                ).compatibility,
            )
        }
    }

    @Test
    fun reportsMissingRequiredCapabilities() {
        val required = HslCapability.PHYSICAL_KEYS or HslCapability.RTK_NMEA
        val result = HslModuleContract.evaluate(
            hello().copy(capabilityMask = HslCapability.PHYSICAL_KEYS),
            requiredCapabilityMask = required,
        )

        assertEquals(HardwareCompatibility.REQUIRED_CAPABILITIES_MISSING, result.compatibility)
        assertEquals(HslCapability.RTK_NMEA, result.missingCapabilityMask)
    }

    private fun hello() = HslModuleHello(
        contractMajor = HslModuleContract.version.major,
        contractMinor = HslModuleContract.version.minor,
        contractPatch = HslModuleContract.version.patch,
        capabilityMask = HslModuleContract.REQUIRED_BASE_CAPABILITIES,
        firmwareMajor = 1,
        firmwareMinor = 0,
        firmwarePatch = 0,
        hardwareRevision = 1,
        bootSessionId = 1,
    )
}
