package com.example.helmet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputCommand
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.HardwareAcknowledgementResult
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareCommandOutcome
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.testfixture.HslHardwareSimulator
import com.example.helmet.service.runtime.HelmetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HslContractSimulatorInstrumentedTest {
    @Test
    fun formalEndpointExercisesHandshakeKeysMmaOutputsAndRtk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(HelmetService.startIntent(context))
        delay(500)
        try {
            HslHardwareSimulator().use { module ->
            val gateway = BoundHardwareGateway(
                context = context,
                devicePath = module.devicePath,
                baudRate = 115_200,
                requiredCapabilityMask = HslCapability.KNOWN_MASK,
            )
            val events = Channel<HardwareEvent>(Channel.UNLIMITED)
            module.start(this)
            val collector = launch { gateway.events.collect(events::send) }
            try {
                withContext(Dispatchers.IO) { gateway.start() }
                withTimeout(5_000) { gateway.status.first { it.linkState == "AWAITING_HELLO" } }

                module.sendHello()
                val hello = receiveEvent<HardwareEvent.ModuleHello>(events)
                assertTrue(hello.compatible)
                val helloAck = module.awaitOutbound(HslMessageType.ACK)
                assertEquals(1, com.example.helmet.core.protocol.HslPayloadCodec.decodeAck(helloAck.payload).acknowledgedSequence)

                module.sendHeartbeat()
                receiveEvent<HardwareEvent.Heartbeat>(events)
                withTimeout(5_000) { gateway.status.first { it.connected } }

                module.sendKey()
                val key = receiveEvent<HardwareEvent.Key>(events)
                gateway.acknowledge(
                    requireNotNull(key.acknowledgement),
                    HardwareAcknowledgementResult.SUCCESS.wireValue,
                )
                assertEquals(3, com.example.helmet.core.protocol.HslPayloadCodec.decodeAck(
                    module.awaitOutbound(HslMessageType.ACK).payload,
                ).acknowledgedSequence)

                module.sendMma8452Sample()
                val sensor = receiveEvent<HardwareEvent.SensorSample>(events)
                assertEquals(1_000, sensor.accelerationZMilliG)

                val nmea = "\$GNGGA,123519,3000.0000,N,11400.0000,E,4,12,0.7,35.0,M,0.0,M,0.8,0042*4E\r\n"
                    .encodeToByteArray()
                module.sendRtkNmea(nmea)
                assertArrayEquals(nmea, receiveEvent<HardwareEvent.ProtocolFrame>(events).payload)

                val outputPayload = HslOutputPayloadCodec.encode(
                    HslOutputCommand(
                        requestId = 9,
                        active = true,
                        actionMask = HslOutputCommand.ALL_ACTIONS,
                    ),
                )
                val outputResult = gateway.sendForResult(
                    HardwareCommand(HslMessageType.SET_OUTPUT, HslFlags.ACK_REQUIRED, 20, outputPayload),
                )
                assertEquals(HardwareCommandOutcome.ACKNOWLEDGED, outputResult.outcome)
                assertArrayEquals(outputPayload, module.awaitOutbound(HslMessageType.SET_OUTPUT).payload)

                val correction = byteArrayOf(0xD3.toByte(), 0x00, 0x00, 0x47, 0xEA.toByte(), 0x4B)
                val correctionResult = gateway.sendForResult(
                    HardwareCommand(HslMessageType.RTK_CORRECTION, HslFlags.ACK_REQUIRED, 21, correction),
                )
                assertEquals(HardwareCommandOutcome.ACKNOWLEDGED, correctionResult.outcome)
                assertArrayEquals(correction, module.awaitOutbound(HslMessageType.RTK_CORRECTION).payload)
            } finally {
                collector.cancel()
                withContext(Dispatchers.IO) { gateway.stop() }
            }
            }
        } finally {
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }

    private suspend inline fun <reified T : HardwareEvent> receiveEvent(
        events: Channel<HardwareEvent>,
    ): T = withTimeout(5_000) { events.receiveAsFlow().filterIsInstance<T>().first() }
}
