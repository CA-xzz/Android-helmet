package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.Rtcm3FrameCodec
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareStatus
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RtkCorrectionControllerInstrumentedTest {
    @Test
    fun loopbackNtripFixtureBecomesBoundedHslCorrectionCommands() {
        val correctionFrame = Rtcm3FrameCodec.encode(ByteArray(900) { index -> (index * 13).toByte() })
        val server = ServerSocket(0)
        val serverThread = thread(start = true, name = "board-ntrip-fixture") {
            server.use { listener ->
                listener.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val value = input.read()
                        if (value < 0) break
                        tail = (tail + value.toChar()).takeLast(4)
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: gnss/data\r\n\r\n".toByteArray())
                        write(correctionFrame)
                        flush()
                    }
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val commands = Collections.synchronizedList(mutableListOf<HardwareCommand>())
        val forwarded = CountDownLatch(2)
        val controller = RtkCorrectionController(
            scope = scope,
            deviceId = "instrumented-rtk-fixture",
            config = RtkRuntimeConfig(
                enabled = true,
                ntripUrl = "http://127.0.0.1:${server.localPort}/MOUNT",
                username = "fixture",
                password = "fixture-secret",
            ),
            hardwareStatus = {
                HardwareStatus(connected = true, simulated = false, linkState = "FIXTURE_CONNECTED")
            },
            correctionSink = { command ->
                commands.add(command)
                forwarded.countDown()
            },
        )

        try {
            controller.acceptReceiverBytes(
                (sentence("GNGGA,123519,3112.0000,N,12124.0000,E,1,12,0.8,10.0,M,8.0,M,,") +
                    "\r\n").toByteArray(),
            )
            controller.start()
            assertTrue("RTCM correction was not forwarded", forwarded.await(10, TimeUnit.SECONDS))
            val captured = synchronized(commands) { commands.toList() }
            assertEquals(2, captured.size)
            assertTrue(captured.all { it.type == HslMessageType.RTK_CORRECTION && it.flags == 0 })
            assertTrue(captured.all { it.payload.size <= RtkCorrectionPacketizer.MAX_HSL_CHUNK_BYTES })
            assertArrayEquals(
                correctionFrame,
                captured.fold(ByteArray(0)) { bytes, command -> bytes + command.payload },
            )
            assertTrue(controller.status.value.correctionFrames >= 1)
        } finally {
            controller.close()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
