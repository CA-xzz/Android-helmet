package com.example.helmet.service.runtime

import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.protocol.HslLocalIntercomStatus
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.LocalIntercomAction
import com.example.helmet.core.protocol.LocalIntercomCodec
import com.example.helmet.core.protocol.LocalIntercomModuleState
import com.example.helmet.core.protocol.LocalIntercomPayloadCodec
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntercomControllerTest {
    @Test
    fun persistedExplicitCommandReplaysTheSameActionAndBusinessRequestId() = runBlocking {
        val commands = mutableListOf<HardwareCommand>()
        val controller = LocalIntercomController(
            config = LocalIntercomRuntimeConfig(enabled = true, groupId = 42, channel = 7, keySlot = 3),
            hardwareStatus = { HardwareStatus(connected = true, simulated = false) },
            commandSink = commands::add,
            requestIdSource = { 0x1020_3040 },
        )
        controller.acceptModuleStatus(
            LocalIntercomPayloadCodec.encodeStatus(status(LocalIntercomModuleState.READY)),
        )
        val planned = controller.planToggleCommand()
        assertEquals(LocalIntercomAction.START_TRANSMIT, planned.action)

        assertTrue(controller.executePlanned(planned))
        assertTrue(controller.executePlanned(planned))

        assertEquals(2, commands.size)
        assertArrayEquals(commands[0].payload, commands[1].payload)
        commands.forEach { command ->
            val decoded = LocalIntercomPayloadCodec.decodeCommand(command.payload)
            assertEquals(LocalIntercomAction.START_TRANSMIT, decoded.action)
            assertEquals(0x1020_3040L, decoded.requestId)
        }
    }

    @Test
    fun togglePlanningFreezesAConcreteTargetForEveryRuntimeState() {
        assertEquals(LocalIntercomAction.START_TRANSMIT, plannedLocalIntercomAction(LocalIntercomState.READY))
        assertEquals(LocalIntercomAction.START_TRANSMIT, plannedLocalIntercomAction(LocalIntercomState.RECEIVING))
        assertEquals(LocalIntercomAction.STOP_TRANSMIT, plannedLocalIntercomAction(LocalIntercomState.TRANSMITTING))
        assertEquals(
            LocalIntercomAction.STOP_TRANSMIT,
            plannedLocalIntercomAction(LocalIntercomState.REQUESTING_TRANSMIT),
        )
        assertEquals(LocalIntercomAction.JOIN, plannedLocalIntercomAction(LocalIntercomState.UNAVAILABLE))
        assertEquals(LocalIntercomAction.JOIN, plannedLocalIntercomAction(LocalIntercomState.FAULT))
    }

    @Test
    fun joinsProvisionedGroupThenRequestsHalfDuplexTransmit() = runBlocking {
        val commands = mutableListOf<HardwareCommand>()
        val controller = LocalIntercomController(
            config = LocalIntercomRuntimeConfig(true, true, 42, 7, 3),
            hardwareStatus = { HardwareStatus(connected = true, simulated = false) },
            commandSink = commands::add,
        )

        assertTrue(controller.ensureJoined())
        assertEquals(LocalIntercomState.JOINING, controller.status.value.state)
        val join = LocalIntercomPayloadCodec.decodeCommand(commands.single().payload)
        assertEquals(LocalIntercomAction.JOIN, join.action)
        assertEquals(42, join.groupId)
        assertEquals(7, join.channel)
        assertEquals(3, join.keySlot)

        controller.acceptModuleStatus(
            LocalIntercomPayloadCodec.encodeStatus(status(LocalIntercomModuleState.READY)),
        )
        assertTrue(controller.toggleTransmit())
        assertEquals(2, commands.size)
        assertEquals(HslMessageType.LOCAL_INTERCOM_COMMAND, commands.last().type)
        assertEquals(
            LocalIntercomAction.START_TRANSMIT,
            LocalIntercomPayloadCodec.decodeCommand(commands.last().payload).action,
        )
        assertEquals(LocalIntercomState.REQUESTING_TRANSMIT, controller.status.value.state)
    }

    @Test
    fun rejectsDedicatedIntercomInSimulatedHardwareMode() = runBlocking {
        val controller = LocalIntercomController(
            config = LocalIntercomRuntimeConfig(enabled = true),
            hardwareStatus = { HardwareStatus(connected = true, simulated = true) },
            commandSink = { error("must not send") },
        )

        assertFalse(controller.ensureJoined())
        assertEquals(LocalIntercomState.UNAVAILABLE, controller.status.value.state)
        assertTrue(controller.status.value.lastError!!.contains("dedicated hardware"))
        assertThrows(IllegalArgumentException::class.java) {
            controller.acceptModuleStatus(
                LocalIntercomPayloadCodec.encodeStatus(status(LocalIntercomModuleState.READY)),
            )
        }
        Unit
    }

    private fun status(state: LocalIntercomModuleState) = HslLocalIntercomStatus(
        state = state,
        requestId = 1,
        peerCount = 2,
        codec = LocalIntercomCodec.VENDOR_NARROWBAND,
        sampleRateHertz = 8_000,
        rssiDbm = -95,
        snrTenthsDb = 30,
        packetLossPermille = 12,
        oneWayLatencyMillis = 140,
        transmittedPackets = 10,
        receivedPackets = 20,
        faultCode = 0,
    )
}
