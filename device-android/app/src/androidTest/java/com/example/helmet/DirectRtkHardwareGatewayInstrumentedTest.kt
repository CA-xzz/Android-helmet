package com.example.helmet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.hardware.api.DirectRtkHardwareGateway
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareCommandOutcome
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.testfixture.AndroidTestPtyFixture
import com.example.helmet.service.runtime.HelmetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectRtkHardwareGatewayInstrumentedTest {
    @Test
    fun dedicatedUartCarriesRawNmeaAndRtcmWithoutHslFraming() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(HelmetService.startIntent(context))
        delay(500)
        try {
            AndroidTestPtyFixture().use { pty ->
            val gateway = DirectRtkHardwareGateway(
                context = context,
                devicePath = pty.slavePath,
                baudRate = 115_200,
            )
            try {
                val inboundEvents = Channel<HardwareEvent.ProtocolFrame>(Channel.UNLIMITED)
                val collector = launch {
                    gateway.events.filterIsInstance<HardwareEvent.ProtocolFrame>().collect(inboundEvents::send)
                }
                withContext(Dispatchers.IO) { gateway.start() }
                withTimeout(5_000) { gateway.status.first { it.connected } }

                val nmea = "\$GNGGA,123519,3000.0000,N,11400.0000,E,4,12,0.7,35.0,M,0.0,M,0.8,0042*4E\r\n"
                    .encodeToByteArray()
                val split = 17
                pty.writeFully(nmea.copyOfRange(0, split))
                val first = withTimeout(5_000) { inboundEvents.receive() }
                pty.writeFully(nmea.copyOfRange(split, nmea.size))
                val second = withTimeout(5_000) { inboundEvents.receive() }
                assertEquals(HslMessageType.RTK_NMEA, first.type)
                assertEquals(HslMessageType.RTK_NMEA, second.type)
                assertArrayEquals(nmea, first.payload + second.payload)

                val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x00, 0x47, 0xEA.toByte(), 0x4B)
                val result = gateway.sendForResult(
                    HardwareCommand(
                        type = HslMessageType.RTK_CORRECTION,
                        flags = 0,
                        sequence = 7,
                        payload = rtcm,
                    ),
                )
                assertEquals(HardwareCommandOutcome.SENT, result.outcome)
                val outbound = withContext(Dispatchers.IO) {
                    pty.read(maximumBytes = 64, timeoutMillis = 5_000)
                }
                assertArrayEquals(rtcm, outbound)
                assertTrue(gateway.status.value.connected)
                collector.cancel()
            } finally {
                withContext(Dispatchers.IO) { gateway.stop() }
            }
            }
        } finally {
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }
}
