package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.protocol.HslLocalIntercomStatus
import com.example.helmet.core.protocol.LocalIntercomAction
import com.example.helmet.core.protocol.LocalIntercomCodec
import com.example.helmet.core.protocol.LocalIntercomModuleState
import com.example.helmet.core.protocol.LocalIntercomPayloadCodec
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalIntercomControllerInstrumentedTest {
    @Test
    fun fixtureModuleStatusDrivesJoinAndHalfDuplexCommands() = runBlocking {
        val commands = mutableListOf<HardwareCommand>()
        val controller = LocalIntercomController(
            config = LocalIntercomRuntimeConfig(
                enabled = true,
                fallbackWhenInternetUnavailable = true,
                groupId = 42,
                channel = 7,
                keySlot = 3,
            ),
            hardwareStatus = {
                HardwareStatus(
                    connected = true,
                    simulated = false,
                    linkState = "FIXTURE_CONNECTED",
                )
            },
            commandSink = { commands.add(it) },
        )

        assertTrue(controller.ensureJoined())
        val join = LocalIntercomPayloadCodec.decodeCommand(commands.single().payload)
        assertEquals(LocalIntercomAction.JOIN, join.action)
        assertEquals(42, join.groupId)
        assertEquals(7, join.channel)
        assertEquals(3, join.keySlot)

        controller.acceptModuleStatus(
            LocalIntercomPayloadCodec.encodeStatus(
                HslLocalIntercomStatus(
                    state = LocalIntercomModuleState.READY,
                    requestId = join.requestId,
                    peerCount = 2,
                    codec = LocalIntercomCodec.VENDOR_NARROWBAND,
                    sampleRateHertz = 8_000,
                    rssiDbm = -94,
                    snrTenthsDb = 28,
                    packetLossPermille = 15,
                    oneWayLatencyMillis = 135,
                    transmittedPackets = 100,
                    receivedPackets = 98,
                    faultCode = 0,
                ),
            ),
        )
        assertEquals(LocalIntercomState.READY, controller.status.value.state)
        assertEquals(-94, controller.status.value.rssiDbm)
        assertEquals(15, controller.status.value.packetLossPermille)

        assertTrue(controller.toggleTransmit())
        val transmit = LocalIntercomPayloadCodec.decodeCommand(commands.last().payload)
        assertEquals(LocalIntercomAction.START_TRANSMIT, transmit.action)
        assertEquals(LocalIntercomState.REQUESTING_TRANSMIT, controller.status.value.state)
    }
}
