package com.example.helmet.communication.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpCommunicationClientTest {
    @Test
    fun endpointRequiresHttpsExceptForLoopbackTests() {
        assertEquals(
            "https://communication.example.test",
            HttpCommunicationClient.validateAndNormalizeBaseUrl("https://communication.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpCommunicationClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpCommunicationClient.validateAndNormalizeBaseUrl("http://communication.example.test")
        }
    }

    @Test
    fun responseSequenceMustBeContinuousAndWithinRequestedLimit() {
        HttpCommunicationClient.validateResponseSequence("signal", listOf(41, 42, 43), 40, 3)

        val gap = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.validateResponseSequence("signal", listOf(41, 43), 40, 3)
        }
        assertEquals(true, gap.retryable)

        val overflow = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.validateResponseSequence("command", listOf(1, 2), 0, 1)
        }
        assertEquals(false, overflow.retryable)
    }
}
