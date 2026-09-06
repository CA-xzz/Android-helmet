package com.example.helmet.service.runtime

import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import com.example.helmet.communication.sync.CommandStreamHighWaterRegressionException
import com.example.helmet.communication.sync.CommunicationException
import com.example.helmet.communication.sync.DeviceCommandPage
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
    fun highWaterRegressionQuarantinesOnlyTheExpectedStream() {
        val expected = "00000000-0000-0000-0000-000000000001"
        val replacement = "00000000-0000-0000-0000-000000000002"

        assertEquals(
            CommandHighWaterFailureDisposition.QUARANTINE,
            commandHighWaterFailureDisposition(
                CommandStreamHighWaterRegressionException(expected, 6L, 7L),
                expected,
            ),
        )
        assertEquals(
            CommandHighWaterFailureDisposition.STREAM_REPLACED,
            commandHighWaterFailureDisposition(
                CommandStreamHighWaterRegressionException(replacement, 1L, 7L),
                expected,
            ),
        )
    }
    @Test
    fun httpPollingUsesFastPrimaryAndBoundedMqttFallbackIntervals() {
        val http = RuntimeConfig(
            backendBaseUrl = "https://backend.example.test",
            backendBearerToken = "device-token",
        )
        assertTrue(shouldPollCommandsOverHttp(http))
        assertEquals(2_000L, httpCommandPollIntervalMillis(http))
        assertEquals("HTTP_POLL", deviceCommandTransportName(http))
        assertFalse(shouldPollCommandsOverHttp(http.copy(backendBearerToken = "")))
        assertEquals(null, httpCommandPollIntervalMillis(http.copy(backendBearerToken = "")))
        assertEquals("UNCONFIGURED", deviceCommandTransportName(http.copy(backendBearerToken = "")))
        assertFalse(shouldPollCommandsOverHttp(http.copy(backendBaseUrl = "")))
        assertFalse(shouldPollCommandsOverHttp(http.copy(mqttBrokerUri = "ssl://broker.example.test:8883")))
        assertEquals(
            "INVALID_MQTT_CONFIGURATION",
            deviceCommandTransportName(http.copy(mqttBrokerUri = "ssl://broker.example.test:8883")),
        )
        assertFalse(shouldPollCommandsOverHttp(http.copy(mqttClientCertificateAlias = "helmet-device")))
        assertTrue(
            shouldPollCommandsOverHttp(
                http.copy(
                    mqttBrokerUri = "ssl://broker.example.test:8883",
                    mqttClientCertificateAlias = "helmet-device",
                ),
            ),
        )
        assertEquals(
            30_000L,
            httpCommandPollIntervalMillis(
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
        val mqtt = http.copy(
            mqttBrokerUri = "ssl://broker.example.test:8883",
            mqttClientCertificateAlias = "helmet-device",
        )
        assertTrue(mqttCommandWakeConfigurationIsComplete(mqtt))
        assertFalse(mqttCommandWakeConfigurationIsComplete(mqtt.copy(backendBaseUrl = "")))
        assertFalse(mqttCommandWakeConfigurationIsComplete(mqtt.copy(backendBearerToken = "")))
        assertFalse(mqttCommandWakeConfigurationIsComplete(mqtt.copy(mqttBrokerUri = "")))
        assertFalse(mqttCommandWakeConfigurationIsComplete(mqtt.copy(mqttClientCertificateAlias = "")))
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workPolicyForTrigger(CommunicationWorkTrigger.SELF_CONTINUATION),
        )
        assertTrue(isLoopbackBackendUrl("http://127.0.0.1:18080"))
        assertTrue(isLoopbackBackendUrl("http://localhost:18080"))
        assertFalse(isLoopbackBackendUrl("https://127.0.0.1.example.test"))
        assertFalse(isLoopbackBackendUrl("not a URL"))
        assertEquals(NetworkType.NOT_REQUIRED, requiredNetworkTypeForBackendUrl("http://[::1]:18080"))
        assertEquals(NetworkType.CONNECTED, requiredNetworkTypeForBackendUrl("https://backend.example.test"))
        assertEquals(ExistingWorkPolicy.KEEP, workPolicyForContinuation(continuation = false))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workPolicyForContinuation(continuation = true))
        assertEquals(ExistingWorkPolicy.KEEP, workPolicyForTrigger(CommunicationWorkTrigger.KICK))
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            workPolicyForTrigger(CommunicationWorkTrigger.DURABLE_COMMIT),
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workPolicyForTrigger(CommunicationWorkTrigger.STREAM_REPLACED),
        )
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            workPolicyForTrigger(CommunicationWorkTrigger.CONFIG_REVISION_CHANGED),
        )
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
    fun communicationContinuationRunsForAnyRemainingDurableItem() {
        assertFalse(
            communicationContinuationRequired(
                fetchedMoreCommands = false,
                pendingCallCount = 0,
                pendingApplicationCount = 0,
                pendingAckCount = 0,
                pendingBroadcastReceiptCount = 0,
            ),
        )
        assertTrue(
            communicationContinuationRequired(
                fetchedMoreCommands = false,
                pendingCallCount = 1,
                pendingApplicationCount = 0,
                pendingAckCount = 0,
                pendingBroadcastReceiptCount = 0,
            ),
        )
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

    @Test
    fun commandWorkerRejectsAConfigurationGenerationChange() {
        val expected = RuntimeConfig(
            revision = 7,
            backendBaseUrl = "https://backend.example.test",
            backendBearerToken = "device-token",
            mqttBrokerUri = "ssl://mqtt.example.test:8883",
            mqttClientCertificateAlias = "helmet-device",
        )

        assertTrue(commandBackendConfigurationIsCurrent(expected, expected.copy()))
        assertFalse(commandBackendConfigurationIsCurrent(expected, expected.copy(revision = 8)))
        assertFalse(
            commandBackendConfigurationIsCurrent(
                expected,
                expected.copy(backendBaseUrl = "https://replacement.example.test"),
            ),
        )
        assertFalse(commandBackendConfigurationIsCurrent(expected, expected.copy(backendBearerToken = "rotated")))
        assertFalse(commandBackendConfigurationIsCurrent(expected, expected.copy(mqttBrokerUri = "")))
        assertFalse(commandBackendConfigurationIsCurrent(expected, expected.copy(mqttClientCertificateAlias = "other")))
    }

    @Test
    fun streamConflictIsRetryableOnlyWhenRediscoveryFindsAReplacementStream() = runBlocking {
        val expected = "00000000-0000-0000-0000-000000000001"
        val replacement = "00000000-0000-0000-0000-000000000002"
        val conflict = CommunicationException("stream conflict", retryable = false, statusCode = 409)

        assertEquals(
            StreamBoundFailureDisposition.STREAM_REPLACED,
            streamBoundFailureDisposition(conflict, expected) { replacement },
        )
        assertEquals(
            StreamBoundFailureDisposition.REJECT,
            streamBoundFailureDisposition(conflict, expected) { expected },
        )
        assertEquals(
            StreamBoundFailureDisposition.RETRY,
            streamBoundFailureDisposition(CommunicationException("offline", true), expected) {
                error("non-409 failures must not perform stream discovery")
            },
        )
        assertEquals(
            StreamBoundFailureDisposition.REJECT,
            streamBoundFailureDisposition(CommunicationException("invalid", false, 422), expected) {
                error("non-409 failures must not perform stream discovery")
            },
        )
        listOf(401, 403).forEach { status ->
            assertTrue(
                isRecoverableCommunicationFailure(
                    CommunicationException("authorization changed", false, status),
                ),
            )
            assertEquals(
                StreamBoundFailureDisposition.RETRY,
                streamBoundFailureDisposition(
                    CommunicationException("authorization changed", false, status),
                    expected,
                ) { error("authorization failures must not perform stream discovery") },
            )
        }
        assertTrue(isRecoverableCommunicationFailure(CommunicationException("offline", true)))
        assertFalse(isRecoverableCommunicationFailure(CommunicationException("invalid", false, 422)))
    }

    @Test
    fun pendingCommandIsAppliedOnlyWhileItsServerStreamIsStillCurrent() = runBlocking {
        val streamA = "00000000-0000-0000-0000-000000000001"
        val streamB = "00000000-0000-0000-0000-000000000002"
        var sideEffectCount = 0

        if (commandStreamIsCurrentBeforeApplication(streamA) { streamB }) sideEffectCount += 1
        assertEquals(0, sideEffectCount)
        assertTrue(commandStreamIsCurrentBeforeApplication(streamA) { streamA })
    }

    @Test
    fun identityGateRejectsSameStreamMutationBeforeDeliveryAndApplication() = runBlocking {
        val stream = "00000000-0000-0000-0000-000000000001"
        val page = DeviceCommandPage(stream, 1L, emptyList(), false, null)
        var verificationCount = 0
        var deliveryCount = 0
        var applicationCount = 0

        val beforeDelivery = commandStreamIdentityGateDisposition(
            expectedCommandStreamId = stream,
            discover = { page },
            verify = {
                verificationCount += 1
                false
            },
        )
        if (beforeDelivery == CommandStreamIdentityGateDisposition.CURRENT) deliveryCount += 1
        val beforeApplication = commandStreamIdentityGateDisposition(
            expectedCommandStreamId = stream,
            discover = { page },
            verify = {
                verificationCount += 1
                false
            },
        )
        if (beforeApplication == CommandStreamIdentityGateDisposition.CURRENT) applicationCount += 1

        assertEquals(CommandStreamIdentityGateDisposition.REJECTED, beforeDelivery)
        assertEquals(CommandStreamIdentityGateDisposition.REJECTED, beforeApplication)
        assertEquals(2, verificationCount)
        assertEquals(0, deliveryCount)
        assertEquals(0, applicationCount)
    }

    @Test
    fun identityGateSchedulesReplacementWithoutVerifyingTheOldStream() = runBlocking {
        val expected = "00000000-0000-0000-0000-000000000001"
        val replacement = "00000000-0000-0000-0000-000000000002"
        var verificationCount = 0

        assertEquals(
            CommandStreamIdentityGateDisposition.STREAM_REPLACED,
            commandStreamIdentityGateDisposition(
                expectedCommandStreamId = expected,
                discover = { DeviceCommandPage(replacement, 0L, emptyList(), false, null) },
                verify = {
                    verificationCount += 1
                    true
                },
            ),
        )
        assertEquals(0, verificationCount)
    }
}
