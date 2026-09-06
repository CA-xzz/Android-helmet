package com.example.helmet

import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.DirectRtkHardwareGateway
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareCommandOutcome
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.testfixture.AndroidTestPtyFixture
import com.example.helmet.testfixture.HslHardwareSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DualSerialIsolationInstrumentedTest {
    @Test
    fun hslAndDirectRtkUseIndependentPortsAndEventStreams() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(HelmetService.startIntent(context))
        delay(500)
        try {
            HslHardwareSimulator().use { hslModule ->
                AndroidTestPtyFixture().use { rtkModule ->
                    val hslGateway = BoundHardwareGateway(
                        context = context,
                        devicePath = hslModule.devicePath,
                        baudRate = 115_200,
                        requiredCapabilityMask = HslCapability.KNOWN_MASK,
                    )
                    val rtkGateway = DirectRtkHardwareGateway(
                        context = context,
                        devicePath = rtkModule.slavePath,
                        baudRate = 115_200,
                    )
                    val hslFrames = Channel<HardwareEvent.ProtocolFrame>(Channel.UNLIMITED)
                    val rtkFrames = Channel<HardwareEvent.ProtocolFrame>(Channel.UNLIMITED)
                    hslModule.start(this)
                    val hslCollector = launch {
                        hslGateway.events.filterIsInstance<HardwareEvent.ProtocolFrame>().collect(hslFrames::send)
                    }
                    val rtkCollector = launch {
                        rtkGateway.events.filterIsInstance<HardwareEvent.ProtocolFrame>().collect(rtkFrames::send)
                    }
                    try {
                        withContext(Dispatchers.IO) { hslGateway.start() }
                        withTimeout(5_000) { hslGateway.status.first { it.linkState == "AWAITING_HELLO" } }
                        hslModule.sendHello()
                        withTimeout(5_000) { hslGateway.status.first { it.compatibility.name == "COMPATIBLE" } }
                        hslModule.sendHeartbeat()
                        withTimeout(5_000) { hslGateway.status.first { it.connected } }

                        withContext(Dispatchers.IO) { rtkGateway.start() }
                        withTimeout(5_000) { rtkGateway.status.first { it.connected } }

                        val directNmea = "\$GNGGA,DIRECT,UART4*00\r\n".encodeToByteArray()
                        rtkModule.writeFully(directNmea)
                        assertArrayEquals(directNmea, withTimeout(5_000) { rtkFrames.receive() }.payload)
                        assertNull(withTimeoutOrNull(300) { hslFrames.receive() })

                        val hslNmea = "\$GNGGA,HSL,UART2*00\r\n".encodeToByteArray()
                        hslModule.sendRtkNmea(hslNmea)
                        assertArrayEquals(hslNmea, withTimeout(5_000) { hslFrames.receive() }.payload)
                        assertNull(withTimeoutOrNull(300) { rtkFrames.receive() })

                        val hslCorrection = byteArrayOf(0xD3.toByte(), 0x00, 0x01, 0x11)
                        val hslResult = hslGateway.sendForResult(
                            HardwareCommand(
                                type = HslMessageType.RTK_CORRECTION,
                                flags = HslFlags.ACK_REQUIRED,
                                sequence = 30,
                                payload = hslCorrection,
                            ),
                        )
                        assertEquals(HardwareCommandOutcome.ACKNOWLEDGED, hslResult.outcome)
                        assertArrayEquals(
                            hslCorrection,
                            hslModule.awaitOutbound(HslMessageType.RTK_CORRECTION).payload,
                        )

                        val directCorrection = byteArrayOf(0xD3.toByte(), 0x00, 0x01, 0x22)
                        val directResult = rtkGateway.sendForResult(
                            HardwareCommand(
                                type = HslMessageType.RTK_CORRECTION,
                                flags = 0,
                                sequence = 31,
                                payload = directCorrection,
                            ),
                        )
                        assertEquals(HardwareCommandOutcome.SENT, directResult.outcome)
                        assertArrayEquals(
                            directCorrection,
                            withContext(Dispatchers.IO) { rtkModule.read(64, 5_000) },
                        )
                    } finally {
                        rtkCollector.cancel()
                        hslCollector.cancel()
                        withContext(Dispatchers.IO) { rtkGateway.stop() }
                        withContext(Dispatchers.IO) { hslGateway.stop() }
                    }
                }
            }
        } finally {
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }
}
