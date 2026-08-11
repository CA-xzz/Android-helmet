package com.example.helmet.service.runtime

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.TextBroadcast
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableCommunicationRecoveryInstrumentedTest {
    @Test
    fun communicationQueuesRecoverAfterProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue("explicit enqueue or recover phase is required", phase in setOf(PHASE_ENQUEUE, PHASE_RECOVER))
        when (phase) {
            PHASE_ENQUEUE -> enqueuePendingCommunicationRecords()
            PHASE_RECOVER -> recoverPendingCommunicationRecords()
        }
    }

    private suspend fun enqueuePendingCommunicationRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        check(!evidence.getBoolean(KEY_READY, false)) {
            "a previous communication recovery phase is incomplete; reinstall the test package"
        }
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val calls = CallStore(database)
        val broadcasts = BroadcastStore(database)
        val commands = DeviceCommandStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val nonce = UUID.randomUUID().toString()
        val callId = "durable-call-$nonce"
        val broadcastId = "durable-broadcast-$nonce"
        val now = System.currentTimeMillis()
        val broadcastRequest = JSONObject()
            .put("broadcastId", broadcastId)
            .put("deviceId", deviceId)
            .put("text", "持久通信恢复测试")
            .put("language", "zh-CN")
            .put("priority", 10)
            .put("expiresAtEpochMillis", now + BROADCAST_EXPIRY_MILLIS)
            .put("createdAtEpochMillis", now)
        val createdBroadcast = postJson("/v1/broadcasts", broadcastRequest)
        val commandId = createdBroadcast.getString("commandId")
        val command = HttpCommunicationClient(ONLINE_ENDPOINT, TEST_TOKEN)
            .fetchCommands(deviceId, 0, 100)
            .single { item -> item.commandId == commandId }

        assertTrue(commands.receive(command))
        assertTrue(commands.markApplied(commandId, now + 3))
        assertTrue(
            broadcasts.receive(
                TextBroadcast(
                    broadcastId = broadcastId,
                    deviceId = deviceId,
                    serverSequence = command.serverSequence,
                    text = broadcastRequest.getString("text"),
                    language = broadcastRequest.getString("language"),
                    priority = broadcastRequest.getInt("priority"),
                    expiresAtEpochMillis = broadcastRequest.getLong("expiresAtEpochMillis"),
                    playbackState = BroadcastPlaybackState.RECEIVED,
                    receivedAtEpochMillis = now + 1,
                    playingAtEpochMillis = null,
                    playedAtEpochMillis = null,
                    lastError = null,
                    receiptDeliveryState = DeliveryState.PENDING,
                    receiptAttemptCount = 0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                broadcastId,
                BroadcastPlaybackState.FAILED,
                now + 2,
                TEST_PLAYBACK_ERROR,
            ),
        )
        assertEquals(
            callId,
            calls.createOutgoing(
                deviceId = deviceId,
                relatedEventId = null,
                simulated = true,
                callId = callId,
            )?.callId,
        )

        configStore.save(
            originalConfig.copy(
                backendBaseUrl = STALE_NETWORK_ENDPOINT,
                backendBearerToken = TEST_TOKEN,
            ),
        )
        CommunicationWorker.enqueue(context)
        awaitStaleNetworkWork(context)

        check(
            evidence.edit()
                .putBoolean(KEY_READY, true)
                .putInt(KEY_PROCESS_ID, Process.myPid())
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_CALL_ID, callId)
                .putString(KEY_COMMAND_ID, commandId)
                .putString(KEY_BROADCAST_ID, broadcastId)
                .putString(KEY_ORIGINAL_BASE_URL, originalConfig.backendBaseUrl)
                .putString(KEY_ORIGINAL_TOKEN, originalConfig.backendBearerToken)
                .commit(),
        ) { "failed to persist communication recovery metadata" }
    }

    private suspend fun recoverPendingCommunicationRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        check(evidence.getBoolean(KEY_READY, false)) { "communication enqueue metadata is missing" }
        assertNotEquals(evidence.getInt(KEY_PROCESS_ID, Process.myPid()), Process.myPid())
        val deviceId = requireEvidence(evidence.getString(KEY_DEVICE_ID, null), KEY_DEVICE_ID)
        val callId = requireEvidence(evidence.getString(KEY_CALL_ID, null), KEY_CALL_ID)
        val commandId = requireEvidence(evidence.getString(KEY_COMMAND_ID, null), KEY_COMMAND_ID)
        val broadcastId = requireEvidence(evidence.getString(KEY_BROADCAST_ID, null), KEY_BROADCAST_ID)
        val originalBaseUrl = evidence.getString(KEY_ORIGINAL_BASE_URL, "").orEmpty()
        val originalToken = evidence.getString(KEY_ORIGINAL_TOKEN, "").orEmpty()
        val configStore = RuntimeConfigStore(context)
        val offlineConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val calls = CallStore(database)
        val broadcasts = BroadcastStore(database)
        val commands = DeviceCommandStore(database)
        assertEquals(DeliveryState.PENDING, calls.find(callId)?.deliveryState)
        assertEquals(0, calls.find(callId)?.attemptCount)
        assertEquals(DeviceCommandState.APPLIED, commands.find(commandId)?.state)
        assertEquals(DeliveryState.PENDING, commands.find(commandId)?.ackDeliveryState)
        assertEquals(0, commands.find(commandId)?.ackAttemptCount)
        assertEquals(BroadcastPlaybackState.FAILED, broadcasts.find(broadcastId)?.playbackState)
        assertEquals(DeliveryState.PENDING, broadcasts.find(broadcastId)?.receiptDeliveryState)
        assertEquals(0, broadcasts.find(broadcastId)?.receiptAttemptCount)

        val workManager = WorkManager.getInstance(context)
        val activeRoute = backendWorkRoute(COMMUNICATION_WORK_BASE_NAME, ONLINE_ENDPOINT)
        val previousWorkIds = workManager.workInfos(activeRoute.activeName).map { item -> item.id }.toSet()
        try {
            configStore.save(
                offlineConfig.copy(
                    backendBaseUrl = ONLINE_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            CommunicationWorker.enqueue(context)
            val recovered = withTimeoutOrNull(WORK_TIMEOUT_MILLIS) {
                while (
                    calls.find(callId)?.deliveryState != DeliveryState.DELIVERED ||
                    commands.find(commandId)?.ackDeliveryState != DeliveryState.DELIVERED ||
                    broadcasts.find(broadcastId)?.receiptDeliveryState != DeliveryState.DELIVERED
                ) {
                    delay(WORK_POLL_MILLIS)
                }
                true
            }
            assertTrue(
                "call=${calls.find(callId)} command=${commands.find(commandId)} " +
                    "broadcast=${broadcasts.find(broadcastId)}",
                recovered == true,
            )

            assertTrue(requireNotNull(calls.find(callId)).attemptCount >= 1)
            assertTrue(requireNotNull(commands.find(commandId)).ackAttemptCount >= 1)
            assertTrue(requireNotNull(broadcasts.find(broadcastId)).receiptAttemptCount >= 1)
            assertEquals(TEST_PLAYBACK_ERROR, broadcasts.find(broadcastId)?.lastError)
            val backendCall = getJson("/v1/calls/$callId")
            assertEquals(callId, backendCall.getString("callId"))
            assertEquals(deviceId, backendCall.getString("deviceId"))
            assertEquals("REQUESTED", backendCall.getString("state"))
            val staleWork = workManager.workInfos(
                backendWorkRoute(COMMUNICATION_WORK_BASE_NAME, STALE_NETWORK_ENDPOINT).activeName,
            )
            val activeWork = workManager.workInfos(activeRoute.activeName)
                .filterNot { item -> item.id in previousWorkIds }
            assertTrue(staleWork.isNotEmpty())
            assertTrue(staleWork.all { item -> item.state == WorkInfo.State.CANCELLED })
            assertTrue(activeWork.any { item -> item.state == WorkInfo.State.SUCCEEDED })
            check(evidence.edit().clear().commit()) { "failed to clear communication recovery metadata" }
        } finally {
            configStore.save(
                offlineConfig.copy(
                    backendBaseUrl = originalBaseUrl,
                    backendBearerToken = originalToken,
                ),
            )
        }
    }

    private suspend fun awaitStaleNetworkWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val workName = backendWorkRoute(COMMUNICATION_WORK_BASE_NAME, STALE_NETWORK_ENDPOINT).activeName
        withTimeout(WORK_TIMEOUT_MILLIS) {
            var work = workManager.workInfos(workName)
            while (work.isEmpty()) {
                delay(WORK_POLL_MILLIS)
                work = workManager.workInfos(workName)
            }
            assertTrue(work.all { item -> item.state == WorkInfo.State.ENQUEUED })
        }
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = requestJson("POST", path, body)

    private suspend fun getJson(path: String): JSONObject = requestJson("GET", path, null)

    private suspend fun requestJson(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            val connection = (URL(ONLINE_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 10_000
                doInput = true
                setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
                setRequestProperty("X-Actor-Id", "durable-communication-test")
                setRequestProperty("X-Actor-Role", "DISPATCHER")
                if (bytes != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(bytes.size)
                }
            }
            try {
                if (bytes != null) connection.outputStream.use { stream -> stream.write(bytes) }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader().use { reader -> reader.readText() }
                check(status in 200..299) { "backend HTTP $status: $text" }
                JSONObject(text)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun WorkManager.workInfos(name: String): List<WorkInfo> =
        withContext(Dispatchers.IO) { getWorkInfosForUniqueWork(name).get() }

    private fun requireEvidence(value: String?, key: String): String =
        requireNotNull(value?.takeIf(String::isNotBlank)) { "$key is missing" }

    companion object {
        private const val PHASE_ARGUMENT = "durableCommunicationPhase"
        private const val PHASE_ENQUEUE = "enqueue"
        private const val PHASE_RECOVER = "recover"
        private const val EVIDENCE_PREFERENCES = "durable_communication_recovery_evidence"
        private const val KEY_READY = "ready"
        private const val KEY_PROCESS_ID = "process_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CALL_ID = "call_id"
        private const val KEY_COMMAND_ID = "command_id"
        private const val KEY_BROADCAST_ID = "broadcast_id"
        private const val KEY_ORIGINAL_BASE_URL = "original_base_url"
        private const val KEY_ORIGINAL_TOKEN = "original_token"
        private const val COMMUNICATION_WORK_BASE_NAME = "helmet-communication-sync"
        private const val STALE_NETWORK_ENDPOINT = "https://unreachable.invalid"
        private const val ONLINE_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
        private const val TEST_PLAYBACK_ERROR = "DURABLE_PLAYBACK_FAILURE"
        private const val BROADCAST_EXPIRY_MILLIS = 600_000L
        private const val WORK_TIMEOUT_MILLIS = 30_000L
        private const val WORK_POLL_MILLIS = 100L
    }
}
