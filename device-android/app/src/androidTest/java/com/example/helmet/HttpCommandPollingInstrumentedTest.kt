package com.example.helmet

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.service.runtime.HelmetService
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    @Test
    fun foregroundServiceAutomaticallyAppliesHttpCallControlCommands() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val callStore = CallStore(database)
        val commandStore = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val nonce = UUID.randomUUID().toString()
        val acceptedCallId = "automatic-accepted-$nonce"
        val rejectedCallId = "automatic-rejected-$nonce"
        context.stopService(HelmetService.startIntent(context))
        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                ),
            )
            requireNotNull(
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = "automatic-call-control-$nonce",
                    simulated = true,
                    callId = acceptedCallId,
                ),
            )
            requireNotNull(
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = "automatic-call-reject-$nonce",
                    simulated = true,
                    callId = rejectedCallId,
                ),
            )
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (callStore.find(acceptedCallId)?.deliveryState != DeliveryState.DELIVERED ||
                    callStore.find(rejectedCallId)?.deliveryState != DeliveryState.DELIVERED
                ) {
                    delay(250)
                }
            }

            val afterSequence = commandStore.maxSequence(deviceId)
            postCallTransition(acceptedCallId, CallState.RINGING)
            postCallTransition(acceptedCallId, CallState.ACCEPTED)
            postCallTransition(acceptedCallId, CallState.ENDED)
            postCallTransition(rejectedCallId, CallState.RINGING)
            postCallTransition(rejectedCallId, CallState.REJECTED)
            val commandIds = waitForCallCommandIds(
                deviceId = deviceId,
                afterSequence = afterSequence,
                expected = setOf(
                    acceptedCallId to CallState.RINGING,
                    acceptedCallId to CallState.ACCEPTED,
                    acceptedCallId to CallState.ENDED,
                    rejectedCallId to CallState.RINGING,
                    rejectedCallId to CallState.REJECTED,
                ),
            )

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (true) {
                    val commandsApplied = commandIds.all { commandId ->
                        val command = commandStore.find(commandId)
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
            context.stopService(HelmetService.startIntent(context))
            configStore.save(originalConfig)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }

    @Test
    fun foregroundServiceAutomaticallyAppliesHttpBroadcastCommand() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val broadcastStore = BroadcastStore(database)
        val commandStore = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val broadcastId = "automatic-http-${UUID.randomUUID()}"
        context.stopService(HelmetService.startIntent(context))
        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                ),
            )
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            val created = postJson(
                "/v1/broadcasts",
                JSONObject()
                    .put("broadcastId", broadcastId)
                    .put("deviceId", deviceId)
                    .put("text", "自动命令轮询验证")
                    .put("language", "zh-CN")
                    .put("priority", 10)
                    .put("expiresAtEpochMillis", System.currentTimeMillis() + 60_000)
                    .put("createdAtEpochMillis", System.currentTimeMillis()),
            )
            val commandId = created.getString("commandId")

            withTimeout(AUTOMATIC_DELIVERY_TIMEOUT_MILLIS) {
                while (true) {
                    val broadcast = broadcastStore.find(broadcastId)
                    val command = commandStore.find(commandId)
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

            val stored = requireNotNull(broadcastStore.find(broadcastId))
            assertTrue(stored.playbackState in TERMINAL_PLAYBACK_STATES)
            assertEquals(DeliveryState.DELIVERED, stored.receiptDeliveryState)
            assertEquals(DeviceCommandState.APPLIED, commandStore.find(commandId)?.state)
            assertEquals(DeliveryState.DELIVERED, commandStore.find(commandId)?.ackDeliveryState)
        } finally {
            context.stopService(HelmetService.startIntent(context))
            configStore.save(originalConfig)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
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
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
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
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
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

    private suspend fun postCallTransition(callId: String, state: CallState) {
        val response = postJson(
            "/v1/calls/$callId/transitions",
            JSONObject()
                .put("state", state.name)
                .put("actorId", "dispatcher-http-poll")
                .put("occurredAtEpochMillis", System.currentTimeMillis())
                .put("reason", JSONObject.NULL),
        )
        assertEquals(state.name, response.getString("state"))
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
        private const val TEST_TOKEN = "stage3-board-integration-token"
        private const val AUTOMATIC_DELIVERY_TIMEOUT_MILLIS = 30_000L
        private val TERMINAL_PLAYBACK_STATES = setOf(
            BroadcastPlaybackState.PLAYED,
            BroadcastPlaybackState.FAILED,
            BroadcastPlaybackState.EXPIRED,
        )
    }
}
