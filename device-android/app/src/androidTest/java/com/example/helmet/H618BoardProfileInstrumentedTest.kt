package com.example.helmet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RuntimeSnapshot
import com.example.helmet.hardware.api.BoardAvailability
import com.example.helmet.hardware.api.H618BoardProfile
import com.example.helmet.core.protocol.HslCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class H618BoardProfileInstrumentedTest {
    @Test
    fun passiveProbeKeepsUnverifiedInterfacesDegraded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val statuses = H618BoardStatusProbe(context).inspect(
            snapshot = RuntimeSnapshot(
                hardwareMode = "UART",
                hardwareConnected = false,
                hardwareLinkState = "DISCONNECTED",
                locationProvider = "none",
                locationHasPosition = false,
            ),
            config = RuntimeConfig(
                simulatorEnabled = false,
                hardwareDevicePath = "/dev/ttyAS2",
                hardwareBaudRate = 115_200,
            ),
            nowEpochMillis = System.currentTimeMillis(),
        ).associateBy { it.resourceId }

        assertTrue(H618BoardProfile.validation.productionSafe)
        assertEquals(BoardAvailability.PIN_CONFLICT, statuses.getValue("camera_power_enable").availability)
        assertEquals(BoardAvailability.PIN_CONFLICT, statuses.getValue("mma8452_i2c0").availability)
        assertEquals(BoardAvailability.WAITING_EXTERNAL, statuses.getValue("hsl_physical_keys").availability)
        assertEquals(BoardAvailability.WAITING_EXTERNAL, statuses.getValue("hsl_local_outputs").availability)
        assertFalse(statuses.getValue("hsl_rtk_bridge").availability == BoardAvailability.RUNNING)
        assertFalse(statuses.getValue("hsl_uart2").availability == BoardAvailability.AVAILABLE)
    }

    @Test
    fun hslSensorRequiresCompatibleHeartbeatAndFreshRealData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        fun mmaStatus(sampleAt: Long?) = H618BoardStatusProbe(context).inspect(
            snapshot = RuntimeSnapshot(
                hardwareMode = "UART",
                hardwareConnected = true,
                hardwareLinkState = "CONNECTED",
                hardwareCompatibility = "COMPATIBLE",
                hardwareContractVersion = "1.0.0",
                hardwareFirmwareVersion = "1.2.3",
                hardwareCapabilityMask = HslCapability.KNOWN_MASK,
                hardwareLastSensorSampleEpochMillis = sampleAt,
            ),
            config = RuntimeConfig(simulatorEnabled = false),
            nowEpochMillis = now,
        ).associateBy { it.resourceId }.getValue("hsl_mma8452")

        assertEquals(BoardAvailability.WAITING_EXTERNAL, mmaStatus(null).availability)
        assertEquals(BoardAvailability.RUNNING, mmaStatus(now - 1_000).availability)
        assertEquals(BoardAvailability.STALE, mmaStatus(now - 20_000).availability)
    }

    @Test
    fun incompatibleContractIsReportedBeforeCapabilities() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val statuses = H618BoardStatusProbe(context).inspect(
            snapshot = RuntimeSnapshot(
                hardwareMode = "UART",
                hardwareCompatibility = "CONTRACT_VERSION_MISMATCH",
                hardwareContractVersion = "2.0.0",
            ),
            config = RuntimeConfig(simulatorEnabled = false),
            nowEpochMillis = System.currentTimeMillis(),
        ).associateBy { it.resourceId }

        assertEquals(BoardAvailability.PROTOCOL_INCOMPATIBLE, statuses.getValue("hsl_uart2").availability)
        assertEquals(
            BoardAvailability.PROTOCOL_INCOMPATIBLE,
            statuses.getValue("hsl_physical_keys").availability,
        )
    }
}
