package com.example.helmet

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.StreamState
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallMediaRecoveryStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.service.runtime.CommunicationWorkTrigger
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.CommunicationWorker
import com.example.helmet.service.runtime.RuntimeStatus
import com.example.helmet.testfixture.PersistentPreferencesTestGuard
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpCommandPollingInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backendBearerToken = requireNonBlankBoardTestArgument(
        InstrumentationRegistry.getArguments().getString(BACKEND_BEARER_TOKEN_ARGUMENT),
        BACKEND_BEARER_TOKEN_ARGUMENT,
    )

    @Test
    fun foregroundServiceAutomaticallyAppliesHttpCallControlCommands() = runBlocking {
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val configStore = RuntimeConfigStore(context)
        val database = HelmetDatabase.get(context)
        val callStore = CallStore(database)
        val commandStore = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val reusableAcceptedCall = callStore.active()?.also { activeCall ->
            require(activeCall.simulated) {
                "refusing to modify a pre-existing non-simulated active call"
            }
            require(
                    activeCall.deviceId == deviceId &&
                    activeCall.direction == CallDirection.OUTGOING_DEVICE &&
                    activeCall.state == CallState.REQUESTED &&
                    activeCall.stateSequence == 1L &&
                    activeCall.deliveryState != DeliveryState.REJECTED
            ) {
                "pre-existing simulated active call is not compatible with safe test recovery"
            }
        }
        val configGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            CONFIG_PREFERENCE_FILES,
        )
        val nonce = UUID.randomUUID().toString()
        val generatedAcceptedCallId = "automatic-accepted-$nonce"
        val rejectedCallId = "automatic-rejected-$nonce"
        try {
            require(callStore.active() == reusableAcceptedCall) {
                "active call changed before HTTP command test isolation"
            }
            configStore.update { current ->
                current.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                )
            }
            val acceptedCall = reusableAcceptedCall ?: requireNotNull(
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = "automatic-call-control-$nonce",
                    simulated = true,
                    callId = generatedAcceptedCallId,
                ),
            )
            if (reusableAcceptedCall != null) {
                if (reusableAcceptedCall.deliveryState != DeliveryState.DELIVERED) {
                    assertTrue(
                        callStore.markAttempt(
                            reusableAcceptedCall.callId,
                            reusableAcceptedCall.stateSequence,
                            System.currentTimeMillis(),
                        ),
                    )
                }
                val receipt = HttpCommunicationClient(TEST_ENDPOINT, backendBearerToken)
                    .syncCall(reusableAcceptedCall)
                assertEquals(reusableAcceptedCall.callId, receipt.callId)
                assertEquals(CallState.REQUESTED.name, receipt.acknowledgedState)
                assertEquals(1L, receipt.acknowledgedStateSequence)
                if (reusableAcceptedCall.deliveryState != DeliveryState.DELIVERED) {
                    assertTrue(
                        callStore.markDelivered(
                            reusableAcceptedCall.callId,
                            reusableAcceptedCall.stateSequence,
                            System.currentTimeMillis(),
                        ),
                    )
                }
                assertEquals(
                    DeliveryState.DELIVERED,
                    callStore.find(reusableAcceptedCall.callId)?.deliveryState,
                )
            }
            val acceptedCallId = acceptedCall.callId
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            CommunicationWorker.enqueue(context, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (callStore.find(acceptedCallId)?.deliveryState != DeliveryState.DELIVERED) {
                    delay(250)
                }
            }

            val commandStreamId = HttpCommunicationClient(TEST_ENDPOINT, backendBearerToken)
                .fetchCommandPage(deviceId, 0, 1)
                .commandStreamId
            val afterSequence = commandStore.maxSequence(commandStreamId, deviceId)
            var acceptedTransitionAt = nextCallTransitionEpochMillis(acceptedCall.updatedAtEpochMillis)
            postCallTransition(acceptedCallId, CallState.RINGING, acceptedTransitionAt)
            acceptedTransitionAt = nextCallTransitionEpochMillis(acceptedTransitionAt)
            postCallTransition(acceptedCallId, CallState.ACCEPTED, acceptedTransitionAt)
            acceptedTransitionAt = nextCallTransitionEpochMillis(acceptedTransitionAt)
            postCallTransition(acceptedCallId, CallState.ENDED, acceptedTransitionAt)
            val acceptedCommandIds = waitForCallCommandIds(
                deviceId = deviceId,
                afterSequence = afterSequence,
                expected = setOf(
                    acceptedCallId to CallState.RINGING,
                    acceptedCallId to CallState.ACCEPTED,
                    acceptedCallId to CallState.ENDED,
                ),
            )
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (
                    callStore.find(acceptedCallId)?.state != CallState.ENDED ||
                    acceptedCommandIds.any {
                        commandStore.find(commandStreamId, it)?.state != DeviceCommandState.APPLIED
                    }
                ) {
                    delay(250)
                }
            }

            requireNotNull(
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = "automatic-call-reject-$nonce",
                    simulated = true,
                    callId = rejectedCallId,
                ),
            )
            CommunicationWorker.enqueue(context, CommunicationWorkTrigger.DURABLE_COMMIT)
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (callStore.find(rejectedCallId)?.deliveryState != DeliveryState.DELIVERED) delay(250)
            }
            val rejectedCall = requireNotNull(callStore.find(rejectedCallId))
            var rejectedTransitionAt = nextCallTransitionEpochMillis(rejectedCall.updatedAtEpochMillis)
            postCallTransition(rejectedCallId, CallState.RINGING, rejectedTransitionAt)
            rejectedTransitionAt = nextCallTransitionEpochMillis(rejectedTransitionAt)
            postCallTransition(rejectedCallId, CallState.REJECTED, rejectedTransitionAt)
            val rejectedCommandIds = waitForCallCommandIds(
                deviceId = deviceId,
                afterSequence = afterSequence,
                expected = setOf(
                    rejectedCallId to CallState.RINGING,
                    rejectedCallId to CallState.REJECTED,
                ),
            )
            val commandIds = acceptedCommandIds + rejectedCommandIds

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (true) {
                    val commandsApplied = commandIds.all { commandId ->
                        val command = commandStore.find(commandStreamId, commandId)
                        command?.state == DeviceCommandState.APPLIED &&
                            command.ackDeliveryState == DeliveryState.DELIVERED
                    }
                    if (callStore.find(acceptedCallId)?.state == CallState.ENDED &&
                        callStore.find(acceptedCallId)?.deliveryState == DeliveryState.DELIVERED &&
                        callStore.find(rejectedCallId)?.state == CallState.REJECTED &&
                        callStore.find(rejectedCallId)?.deliveryState == DeliveryState.DELIVERED &&
                        commandsApplied
                    ) {
                        break
                    }
                    delay(250)
                }
            }

            assertEquals(CallState.ENDED, callStore.find(acceptedCallId)?.state)
            assertEquals(CallState.REJECTED, callStore.find(rejectedCallId)?.state)
            assertEquals(5, commandIds.size)
        } finally {
            restoreConfigurationAndService(configGuard)
        }
    }

    @Test
    fun foregroundServiceAutomaticallyAppliesHttpBroadcastCommand() = runBlocking {
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val configStore = RuntimeConfigStore(context)
        val configGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            CONFIG_PREFERENCE_FILES,
        )
        val database = HelmetDatabase.get(context)
        val broadcastStore = BroadcastStore(database)
        val commandStore = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val broadcastId = "automatic-http-${UUID.randomUUID()}"
        try {
            configStore.update { current ->
                current.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                )
            }
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            CommunicationWorker.enqueue(context, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)
            val created = postJson(
                "/v1/broadcasts",
                JSONObject()
                    .put("broadcastId", broadcastId)
                    .put("deviceId", deviceId)
                    .put("text", "自动命令轮询验证")
                    .put("language", "zh-CN")
                    .put("priority", 10)
                    .put("createdAtEpochMillis", System.currentTimeMillis()),
            )
            val commandId = created.getString("commandId")
            val commandStreamId = HttpCommunicationClient(TEST_ENDPOINT, backendBearerToken)
                .fetchCommandPage(deviceId, 0, 1)
                .commandStreamId

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (true) {
                    val broadcast = broadcastStore.find(commandStreamId, broadcastId)
                    val command = commandStore.find(commandStreamId, commandId)
                    if (broadcast?.playbackState in TERMINAL_PLAYBACK_STATES &&
                        broadcast?.receiptDeliveryState == DeliveryState.DELIVERED &&
                        command?.state == DeviceCommandState.APPLIED &&
                        command.ackDeliveryState == DeliveryState.DELIVERED
                    ) {
                        break
                    }
                    delay(250)
                }
            }

            val stored = requireNotNull(broadcastStore.find(commandStreamId, broadcastId))
            assertTrue(stored.playbackState in TERMINAL_PLAYBACK_STATES)
            assertEquals(DeliveryState.DELIVERED, stored.receiptDeliveryState)
            assertEquals(DeviceCommandState.APPLIED, commandStore.find(commandStreamId, commandId)?.state)
            assertEquals(
                DeliveryState.DELIVERED,
                commandStore.find(commandStreamId, commandId)?.ackDeliveryState,
            )
        } finally {
            restoreConfigurationAndService(configGuard)
        }
    }

    @Test
    fun acceptedCallStartsActualCapabilitiesAndHangupReleasesMedia() = runBlocking {
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val configStore = RuntimeConfigStore(context)
        val configGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            CONFIG_PREFERENCE_FILES,
        )
        val database = HelmetDatabase.get(context)
        val calls = CallStore(database)
        val events = EventStore(database)
        val recovery = CallMediaRecoveryStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val callId = "accepted-media-${UUID.randomUUID()}"
        try {
            require(calls.active() == null) { "an existing active call would make this test destructive" }
            configStore.update { current ->
                current.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                )
            }
            val requested = requireNotNull(
                calls.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = "accepted-media-test",
                    simulated = true,
                    callId = callId,
                ),
            )
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            CommunicationWorker.enqueue(context, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (calls.find(callId)?.deliveryState != DeliveryState.DELIVERED) delay(250)
            }

            val acceptedAt = nextCallTransitionEpochMillis(requested.updatedAtEpochMillis)
            postCallTransition(callId, CallState.ACCEPTED, acceptedAt)
            var offer = HttpCommunicationClient(TEST_ENDPOINT, backendBearerToken)
                .fetchCallSignals(callId, 0, 100)
                .firstOrNull { it.type == CallSignalType.OFFER }
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (
                    offer == null &&
                    calls.find(callId)?.state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)
                ) {
                    delay(250)
                    offer = HttpCommunicationClient(TEST_ENDPOINT, backendBearerToken)
                        .fetchCallSignals(callId, 0, 100)
                        .firstOrNull { it.type == CallSignalType.OFFER }
                }
            }
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                assertTrue(offer == null)
                val failedCall = requireNotNull(calls.find(callId))
                assertEquals(CallState.FAILED, failedCall.state)
                assertEquals("WEBRTC_MICROPHONE_UNAVAILABLE", failedCall.lastReason)
                withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                    events.observeRecent(200).first { recent ->
                        recent.any { event ->
                            event.eventType == "CALL_STATUS_PROMPT_REQUESTED" &&
                                JSONObject(event.payloadJson).optString("callId") == callId &&
                                JSONObject(event.payloadJson).optString("state") == CallState.ACCEPTED.name
                        } && recent.any { event ->
                            event.eventType == "WEBRTC_SESSION_FAILED" &&
                                JSONObject(event.payloadJson).optString("callId") == callId &&
                                JSONObject(event.payloadJson).optString("errorCode") == "MICROPHONE_UNAVAILABLE"
                        } && recent.any { event ->
                            event.eventType == "WEBRTC_SESSION_CLOSED" &&
                                JSONObject(event.payloadJson).optString("callId") == callId
                        }
                    }
                }
                assertTrue(recovery.find(callId) == null)
                assertTrue(!RuntimeStatus.snapshot.value.streamAudioEnabled)
                assertTrue(!RuntimeStatus.snapshot.value.streamVideoEnabled)
                CommunicationWorker.enqueue(context, CommunicationWorkTrigger.DURABLE_COMMIT)
                withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                    while (
                        calls.find(callId)?.deliveryState != DeliveryState.DELIVERED ||
                        getJson("/v1/calls/$callId").getString("state") != CallState.FAILED.name
                    ) {
                        delay(250)
                    }
                }
                assertEquals(CallState.FAILED.name, getJson("/v1/calls/$callId").getString("state"))
                return@runBlocking
            }
            val confirmedOffer = requireNotNull(offer) { "WebRTC offer was not generated before terminal state" }
            val offerPayload = JSONObject(confirmedOffer.payloadJson)
            assertTrue(offerPayload.getBoolean("audioEnabled"))
            assertTrue(!offerPayload.getBoolean("videoEnabled"))
            assertTrue(offerPayload.optString("degradedReason").contains("CAMERA"))
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                events.observeRecent(200).first { recent ->
                    recent.any { event ->
                        event.eventType == "CALL_STATUS_PROMPT_REQUESTED" &&
                            JSONObject(event.payloadJson).optString("callId") == callId &&
                            JSONObject(event.payloadJson).optString("state") == CallState.ACCEPTED.name
                    } && recent.any { event ->
                        event.eventType == "WEBRTC_OFFER_SENT" &&
                            JSONObject(event.payloadJson).optString("callId") == callId
                    }
                }
            }
            assertTrue(recovery.find(callId)?.latestOfferSequence != null)

            val backendCall = getJson("/v1/calls/$callId")
            val endedAt = nextCallTransitionEpochMillis(backendCall.getLong("updatedAtEpochMillis"))
            postCallTransition(callId, CallState.ENDED, endedAt)
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (
                    calls.find(callId)?.state != CallState.ENDED ||
                    RuntimeStatus.snapshot.value.streamState != StreamState.IDLE ||
                    recovery.find(callId) != null
                ) {
                    delay(250)
                }
            }
            val snapshot = RuntimeStatus.snapshot.value
            assertTrue(!snapshot.streamAudioEnabled)
            assertTrue(!snapshot.streamVideoEnabled)
            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                events.observeRecent(200).first { recent ->
                    recent.any { event ->
                        event.eventType == "WEBRTC_SESSION_CLOSED" &&
                            JSONObject(event.payloadJson).optString("callId") == callId
                    }
                }
            }
            Unit
        } finally {
            val current = calls.find(callId)
            if (current != null && current.state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) {
                runCatching { calls.transition(callId, CallState.ENDED, "TEST_CLEANUP") }
            }
            restoreConfigurationAndService(configGuard)
        }
    }

    private suspend fun restoreConfigurationAndService(configGuard: PersistentPreferencesTestGuard) {
        withContext(NonCancellable) {
            try {
                configGuard.restore()
            } finally {
                ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
                CommunicationWorker.enqueue(context, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)
            }
        }
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
            setRequestProperty("X-Actor-Id", "dispatcher-http-poll")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            check(status in 200..299) { "backend HTTP $status: $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            doInput = true
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            check(status in 200..299) { "backend HTTP $status: $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postCallTransition(
        callId: String,
        state: CallState,
        occurredAtEpochMillis: Long,
    ) {
        val response = postJson(
            "/v1/calls/$callId/transitions",
            JSONObject()
                .put("state", state.name)
                .put("actorId", "dispatcher-http-poll")
                .put("occurredAtEpochMillis", occurredAtEpochMillis)
                .put("reason", JSONObject.NULL),
        )
        assertEquals(state.name, response.getString("state"))
    }

    private fun nextCallTransitionEpochMillis(previous: Long): Long {
        check(previous < Long.MAX_VALUE) { "call transition time is exhausted" }
        return maxOf(System.currentTimeMillis(), previous + 1)
    }

    private suspend fun waitForCallCommandIds(
        deviceId: String,
        afterSequence: Long,
        expected: Set<Pair<String, CallState>>,
    ): Set<String> = withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
        var matched: Map<Pair<String, CallState>, String>
        do {
            val response = getJson(
                "/v1/device-commands?deviceId=$deviceId&afterSequence=$afterSequence&limit=100",
            )
            val commands = response.getJSONArray("commands")
            matched = buildMap {
                for (index in 0 until commands.length()) {
                    val command = commands.getJSONObject(index)
                    if (command.getString("type") != "CALL_STATE") continue
                    val payload = command.getJSONObject("payload")
                    val key = payload.getString("callId") to CallState.valueOf(payload.getString("state"))
                    if (key in expected) put(key, command.getString("commandId"))
                }
            }
            if (matched.keys != expected) delay(250)
        } while (matched.keys != expected)
        matched.values.toSet()
    }

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val BACKEND_BEARER_TOKEN_ARGUMENT = "backendBearerToken"
        private const val RECOVERY_EVIDENCE_PREFERENCES = "http_command_polling_test_recovery"
        private const val AUTOMATIC_DELIVERY_TIMEOUT_MILLIS = 30_000L
        private val CONFIG_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
        )
        private val TERMINAL_PLAYBACK_STATES = setOf(
            BroadcastPlaybackState.PLAYED,
            BroadcastPlaybackState.FAILED,
            BroadcastPlaybackState.EXPIRED,
        )
    }
}
