package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslModuleHello

object HslModuleContract {
    val version: HardwareContractVersion
        get() = H618BoardProfile.profile.contractVersion

    const val REQUIRED_BASE_CAPABILITIES: Long = HslCapability.PHYSICAL_KEYS or
        HslCapability.LED_OUTPUT or HslCapability.VIBRATION_OUTPUT or
        HslCapability.BUZZER_OUTPUT or HslCapability.MMA8452_ACCELEROMETER

    fun evaluate(
        hello: HslModuleHello,
        requiredCapabilityMask: Long = REQUIRED_BASE_CAPABILITIES,
    ): HslModuleCompatibilityResult {
        require(requiredCapabilityMask and HslCapability.KNOWN_MASK.inv() == 0L)
        val expected = version
        val versionCompatible = hello.contractMajor == expected.major && hello.contractMinor >= expected.minor
        val missing = requiredCapabilityMask and hello.capabilityMask.inv()
        val compatibility = when {
            !versionCompatible -> HardwareCompatibility.CONTRACT_VERSION_MISMATCH
            missing != 0L -> HardwareCompatibility.REQUIRED_CAPABILITIES_MISSING
            else -> HardwareCompatibility.COMPATIBLE
        }
        return HslModuleCompatibilityResult(compatibility, missing)
    }

    fun capabilityNames(mask: Long): Set<String> = buildSet {
        CAPABILITIES.forEach { (bit, name) -> if (mask and bit != 0L) add(name) }
    }

    private val CAPABILITIES = listOf(
        HslCapability.PHYSICAL_KEYS to "PHYSICAL_KEYS",
        HslCapability.LED_OUTPUT to "LED_OUTPUT",
        HslCapability.VIBRATION_OUTPUT to "VIBRATION_OUTPUT",
        HslCapability.BUZZER_OUTPUT to "BUZZER_OUTPUT",
        HslCapability.MMA8452_ACCELEROMETER to "MMA8452_ACCELEROMETER",
        HslCapability.RTK_NMEA to "RTK_NMEA",
        HslCapability.RTK_CORRECTION to "RTK_CORRECTION",
        HslCapability.LOCAL_INTERCOM to "LOCAL_INTERCOM",
    )
}

data class HslModuleCompatibilityResult(
    val compatibility: HardwareCompatibility,
    val missingCapabilityMask: Long,
)
