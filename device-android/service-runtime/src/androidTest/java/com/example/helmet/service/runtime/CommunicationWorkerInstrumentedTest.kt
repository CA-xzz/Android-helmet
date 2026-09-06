package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.TextBroadcast
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.TextBroadcastEntity
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationWorkerInstrumentedTest {
    @Test
    fun thrownPlaybackFailureMakesTheDurableBroadcastTerminalBeforeCommandFailureAck() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val broadcasts = BroadcastStore(database)
        val commands = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val deviceId = "playback-throw-$nonce"
        val broadcastId = "broadcast-$nonce"
        val receivedAt = 100L
        val command = DeviceCommand(
            commandId = "command-$nonce",
            deviceId = deviceId,
            serverSequence = 1L,
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = JSONObject()
                .put("broadcastId", broadcastId)
                .put("text", "failure")
                .put("language", "en-US")
                .put("priority", 1)
                .put("expiresAtEpochMillis", JSONObject.NULL)
                .toString(),
            createdAtEpochMillis = 99L,
            state = DeviceCommandState.RECEIVED,
            receivedAtEpochMillis = receivedAt,
            appliedAtEpochMillis = null,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )
        assertTrue(commands.receive(stream, command))
        assertTrue(
            broadcasts.receive(
                stream,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "failure",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    receivedAt,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                stream,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYING,
                receivedAt + 1L,
            ),
        )

        assertEquals(
            receivedAt + 2L,
            terminalizeFailedBroadcastIfPresent(
                command,
                stream,
                broadcasts,
                receivedAt,
                "TTS_THROWN",
            ),
        )
        val stored = requireNotNull(broadcasts.find(stream, broadcastId))
        assertEquals(BroadcastPlaybackState.FAILED, stored.playbackState)
        assertEquals(receivedAt + 2L, stored.playedAtEpochMillis)
        assertEquals("TTS_THROWN", stored.lastError)
        assertEquals(DeliveryState.PENDING, stored.receiptDeliveryState)
    }

    @Test
    fun exhaustedLegacyBroadcastTimelineCannotKeepItsCommandPendingApplication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val broadcasts = BroadcastStore(database)
        val commands = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val deviceId = "legacy-timeline-$nonce"
        val broadcastId = "broadcast-$nonce"
        val command = DeviceCommand(
            commandId = "command-$nonce",
            deviceId = deviceId,
            serverSequence = 1L,
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = JSONObject().put("broadcastId", broadcastId).toString(),
            createdAtEpochMillis = Long.MAX_VALUE - 1L,
            state = DeviceCommandState.RECEIVED,
            receivedAtEpochMillis = Long.MAX_VALUE,
            appliedAtEpochMillis = null,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )
        assertTrue(commands.receive(stream, command))
        assertTrue(
            database.textBroadcastDao().insert(
                TextBroadcastEntity(
                    broadcastId = broadcastId,
                    commandStreamId = stream,
                    deviceId = deviceId,
                    serverSequence = 1L,
                    text = "legacy",
                    language = "en-US",
                    priority = 1,
                    expiresAtEpochMillis = null,
                    playbackState = BroadcastPlaybackState.RECEIVED.name,
                    receivedAtEpochMillis = Long.MAX_VALUE,
                    playingAtEpochMillis = null,
                    playedAtEpochMillis = null,
                    lastError = null,
                    receiptLastError = null,
                    receiptDeliveryState = DeliveryState.PENDING.name,
                    receiptAttemptCount = 0,
                    lastReceiptAttemptAtEpochMillis = null,
                    receiptDeliveredAtEpochMillis = null,
                ),
            ) != -1L,
        )

        assertEquals(
            null,
            terminalizeFailedBroadcastIfPresent(
                command,
                stream,
                broadcasts,
                Long.MAX_VALUE,
                "TIMELINE_EXHAUSTED",
            ),
        )
        assertTrue(commands.markFailed(stream, command.commandId, Long.MAX_VALUE, "TIMELINE_EXHAUSTED"))
        assertEquals(0, commands.pendingApplicationCount(stream, deviceId))
        assertEquals(1, commands.pendingAckCount(stream, deviceId))
        assertEquals(DeviceCommandState.FAILED, commands.find(stream, command.commandId)?.state)
    }

    @Test
    fun workerSyncsCallAppliesOrderedCommandsAndReportsTtsFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val configSnapshot = captureRuntimeConfigurationForTest(context)
        val database = HelmetDatabase.get(context)
        val callStore = CallStore(database)
        val broadcastStore = BroadcastStore(database)
        val commandStore = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val nonce = UUID.randomUUID().toString()
        val callId = "call-$nonce"
        val broadcastId = "broadcast-$nonce"
        requireNotNull(
            callStore.createOutgoing(
                deviceId = deviceId,
                relatedEventId = "event-$nonce",
                simulated = true,
                callId = callId,
            ),
        )
        try {
            configStore.saveForInstrumentationTest(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            val first = TestListenableWorkerBuilder<CommunicationWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, first.javaClass)
            assertEquals(DeliveryState.DELIVERED, callStore.find(callId)?.deliveryState)

            val client = HttpCommunicationClient(TEST_ENDPOINT, TEST_TOKEN)
            val ice = client.fetchIceConfiguration(callId, deviceId)
            assertTrue(ice.servers.any { server -> server.username != null && server.credential != null })
            val offerSignal = CallSignal(
                signalId = "offer-$nonce",
                callId = callId,
                serverSequence = null,
                senderId = deviceId,
                type = CallSignalType.OFFER,
                payloadJson = JSONObject().put("sdp", "v=0\r\na=fingerprint:sha-256 00\r\n").toString(),
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            assertEquals(1L, client.sendCallSignal(offerSignal).serverSequence)
            val storedSignals = client.fetchCallSignals(callId, 0)
            assertEquals(listOf(CallSignalType.OFFER), storedSignals.map(CallSignal::type))

            val ringing = postJson(
                "/v1/calls/$callId/transitions",
                JSONObject()
                    .put("state", "RINGING")
                    .put("actorId", "dispatcher-1")
                    .put("occurredAtEpochMillis", System.currentTimeMillis())
                    .put("reason", JSONObject.NULL),
            )
            assertEquals("RINGING", ringing.getString("state"))
            val broadcast = postJson(
                "/v1/broadcasts",
                JSONObject()
                    .put("broadcastId", broadcastId)
                    .put("deviceId", deviceId)
                    .put("text", "请立即撤离")
                    .put("language", "zh-CN")
                    .put("priority", 10)
                    .put("expiresAtEpochMillis", System.currentTimeMillis() + 60_000)
                    .put("createdAtEpochMillis", System.currentTimeMillis()),
            )
            val broadcastCommandId = broadcast.getString("commandId")
            val commandStreamId = HttpCommunicationClient(TEST_ENDPOINT, TEST_TOKEN)
                .fetchCommandPage(deviceId, 0, 1)
                .commandStreamId

            val second = TestListenableWorkerBuilder<CommunicationWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, second.javaClass)
            assertEquals(CallState.RINGING, callStore.find(callId)?.state)
            assertEquals(DeliveryState.DELIVERED, callStore.find(callId)?.deliveryState)
            val storedBroadcast = broadcastStore.find(commandStreamId, broadcastId)
            assertEquals(BroadcastPlaybackState.FAILED, storedBroadcast?.playbackState)
            assertTrue(storedBroadcast?.lastError?.startsWith("TTS_") == true)
            assertEquals(DeliveryState.DELIVERED, storedBroadcast?.receiptDeliveryState)
            val storedCommand = commandStore.find(commandStreamId, broadcastCommandId)
            assertEquals(DeviceCommandState.APPLIED, storedCommand?.state)
            assertEquals(DeliveryState.DELIVERED, storedCommand?.ackDeliveryState)
        } finally {
            restoreRuntimeConfigurationForTest(context, configSnapshot)
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
            setRequestProperty("X-Actor-Id", "dispatcher-1")
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

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
    }
}
