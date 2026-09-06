package com.example.helmet.service.runtime

import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.protocol.HslCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HslRequiredCapabilitiesTest {
    @Test
    fun defaultHslModeRequiresBaseOutputsSensorsKeysAndNmea() {
        val mask = requiredHslCapabilityMask(RuntimeConfig())

        val base = HslCapability.PHYSICAL_KEYS or HslCapability.LED_OUTPUT or
            HslCapability.VIBRATION_OUTPUT or HslCapability.BUZZER_OUTPUT or
            HslCapability.MMA8452_ACCELEROMETER
        assertEquals(base or HslCapability.RTK_NMEA, mask)
    }

    @Test
    fun directRtkDoesNotRequireRtkCapabilitiesFromHsl() {
        val mask = requiredHslCapabilityMask(
            RuntimeConfig(
                rtk = RtkRuntimeConfig(transportMode = RtkTransportMode.DIRECT_UART4),
            ),
        )

        assertEquals(0L, mask and (HslCapability.RTK_NMEA or HslCapability.RTK_CORRECTION))
    }

    @Test
    fun enabledNtripAndIntercomAddConditionalCapabilities() {
        val mask = requiredHslCapabilityMask(
            RuntimeConfig(
                rtk = RtkRuntimeConfig(enabled = true),
                localIntercom = LocalIntercomRuntimeConfig(enabled = true),
            ),
        )

        assertTrue(mask and HslCapability.RTK_CORRECTION != 0L)
        assertTrue(mask and HslCapability.LOCAL_INTERCOM != 0L)
    }
}
