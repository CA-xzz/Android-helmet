package com.example.helmet.service.runtime

import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import com.example.helmet.core.model.RuntimeConfig
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCommandPollingTest {
    @Test
    fun httpFallbackPollsOnlyWithCompleteBackendAndNoMqttConfiguration() {
        val http = RuntimeConfig(
            backendBaseUrl = "https://backend.example.test",
            backendBearerToken = "device-token",
        )
        assertTrue(shouldPollCommandsOverHttp(http))
        assertEquals("HTTP_POLL", deviceCommandTransportName(http))
        assertFalse(shouldPollCommandsOverHttp(http.copy(backendBearerToken = "")))
        assertEquals("UNCONFIGURED", deviceCommandTransportName(http.copy(backendBearerToken = "")))
        assertFalse(shouldPollCommandsOverHttp(http.copy(backendBaseUrl = "")))
        assertFalse(shouldPollCommandsOverHttp(http.copy(mqttBrokerUri = "ssl://broker.example.test:8883")))
        assertEquals(
            "INVALID_MQTT_CONFIGURATION",
            deviceCommandTransportName(http.copy(mqttBrokerUri = "ssl://broker.example.test:8883")),
        )
        assertFalse(shouldPollCommandsOverHttp(http.copy(mqttClientCertificateAlias = "helmet-device")))
        assertFalse(
            shouldPollCommandsOverHttp(
                http.copy(
                    mqttBrokerUri = "ssl://broker.example.test:8883",
                    mqttClientCertificateAlias = "helmet-device",
                ),
            ),
        )
        assertEquals(
            "MQTT",
            deviceCommandTransportName(
                http.copy(
                    mqttBrokerUri = "ssl://broker.example.test:8883",
                    mqttClientCertificateAlias = "helmet-device",
                ),
            ),
        )
        assertTrue(isLoopbackBackendUrl("http://127.0.0.1:18080"))
        assertTrue(isLoopbackBackendUrl("http://localhost:18080"))
        assertFalse(isLoopbackBackendUrl("https://127.0.0.1.example.test"))
        assertFalse(isLoopbackBackendUrl("not a URL"))
        assertEquals(NetworkType.NOT_REQUIRED, requiredNetworkTypeForBackendUrl("http://[::1]:18080"))
        assertEquals(NetworkType.CONNECTED, requiredNetworkTypeForBackendUrl("https://backend.example.test"))
        assertEquals(ExistingWorkPolicy.KEEP, workPolicyForContinuation(continuation = false))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workPolicyForContinuation(continuation = true))
        listOf(
            "helmet-communication-sync",
            "helmet-safety-alert-upload",
            "helmet-track-upload",
            "helmet-media-upload",
        ).forEach { baseName ->
            val loopbackRoute = backendWorkRoute(baseName, "http://127.0.0.1:18080")
            val networkRoute = backendWorkRoute(baseName, "https://backend.example.test")
            assertEquals(NetworkType.NOT_REQUIRED, loopbackRoute.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, networkRoute.requiredNetworkType)
            assertEquals("$baseName-loopback-v2", loopbackRoute.activeName)
            assertEquals("$baseName-network-v2", networkRoute.activeName)
            assertEquals(loopbackRoute.activeName, networkRoute.staleName)
            assertEquals(networkRoute.activeName, loopbackRoute.staleName)
        }
    }

    @Test
    fun pollFlowStartsImmediatelyAndRepeats() = runBlocking {
        val polls = withTimeout(1_000) {
            httpCommandPollFlow(intervalMillis = 1)
                .take(3)
                .toList()
        }

        assertEquals(3, polls.size)
    }
}
