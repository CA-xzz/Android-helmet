package com.example.helmet.service.runtime

import com.example.helmet.core.protocol.Rtcm3FrameCodec
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NtripCorrectionClientTest {
    @Test
    fun validatesEndpointSecurityAndRedactsCredentials() {
        val endpoint = NtripEndpoint.parse("https://caster.example:8443/MOUNT")

        assertTrue(endpoint.secure)
        assertEquals("caster.example:8443", endpoint.authority)
        assertEquals("https://caster.example:8443/MOUNT", endpoint.redactedDescription)
        assertFalse(endpoint.redactedDescription.contains("secret"))
        assertThrows(IllegalArgumentException::class.java) {
            NtripEndpoint.parse("http://caster.example/MOUNT")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtripEndpoint.parse("https://user:secret@caster.example/MOUNT")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtripEndpoint.parse("https://caster.example/MOUNT?token=secret")
        }
        assertFalse(NtripEndpoint.parse("http://127.0.0.1:2101/MOUNT").secure)
    }

    @Test
    fun requestCarriesNtripHeadersAndChecksumValidGga() {
        val gga = sentence("GNGGA,123519,3112.0000,N,12124.0000,E,1,12,0.8,10.0,M,8.0,M,,")
        val request = NtripWireProtocol.buildRequest(
            NtripEndpoint.parse("https://caster.example/MOUNT"),
            "helmet",
            "secret",
            gga,
        ).toString(Charsets.ISO_8859_1)

        assertTrue(request.startsWith("GET /MOUNT HTTP/1.1\r\n"))
        assertTrue(request.contains("Ntrip-Version: Ntrip/2.0\r\n"))
        assertTrue(request.contains("Ntrip-GGA: $gga\r\n"))
        assertTrue(request.contains("Authorization: Basic "))
        assertFalse(request.contains("helmet:secret"))
    }

    @Test
    fun streamsOnlyCrcValidRtcmFramesFromLoopbackCaster() {
        val server = ServerSocket(0)
        val receivedRequest = AtomicReference<String>()
        val validFrame = Rtcm3FrameCodec.encode(ByteArray(64) { index -> index.toByte() })
        val corruptFrame = validFrame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val serverThread = thread(start = true, name = "ntrip-test-caster") {
            server.use { listener ->
                listener.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val request = StringBuilder()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val value = input.read()
                        if (value < 0) break
                        request.append(value.toChar())
                        tail = (tail + value.toChar()).takeLast(4)
                    }
                    receivedRequest.set(request.toString())
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: gnss/data\r\n\r\n".toByteArray())
                        write(corruptFrame)
                        write(validFrame.copyOfRange(0, 17))
                        flush()
                        write(validFrame.copyOfRange(17, validFrame.size))
                        flush()
                    }
                }
            }
        }
        val decoded = mutableListOf<ByteArray>()
        val client = NtripCorrectionClient(
            endpoint = NtripEndpoint.parse("http://127.0.0.1:${server.localPort}/MOUNT"),
            username = "helmet",
            password = "secret",
            connectTimeoutMillis = 2_000,
            readPollMillis = 100,
        )

        val result = client.stream(ggaProvider = { null }, onCorrectionFrame = decoded::add)
        serverThread.join(2_000)

        assertEquals(1, result.correctionFrames)
        assertEquals(1, result.decoderCrcErrors)
        assertArrayEquals(validFrame, decoded.single())
        assertTrue(receivedRequest.get().contains("GET /MOUNT HTTP/1.1"))
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
