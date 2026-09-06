package com.example.helmet

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.RuntimeStatus
import com.example.helmet.testfixture.capturePreferenceFilesForTest
import com.example.helmet.testfixture.restorePreferenceFilesForTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

internal fun requireNonBlankBoardTestArgument(value: String?, argumentName: String): String =
    requireNotNull(value?.takeIf(String::isNotBlank)) {
        "instrumentation argument $argumentName is required and must not be blank"
    }

@RunWith(AndroidJUnit4::class)
class AutomaticStartupQueueRecoveryInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backendBearerToken = requireNonBlankBoardTestArgument(
        InstrumentationRegistry.getArguments().getString(BACKEND_BEARER_TOKEN_ARGUMENT),
        BACKEND_BEARER_TOKEN_ARGUMENT,
    )

    @Test
    fun applicationStartupRecoversEveryDurableQueue() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue(
            "seed, recover, or cleanup phase is required",
            phase in setOf(PHASE_SEED, PHASE_RECOVER, PHASE_CLEANUP),
        )
        when (phase) {
            PHASE_SEED -> seedPendingRecords()
            PHASE_RECOVER -> verifyAutomaticRecovery()
            PHASE_CLEANUP -> cleanupRecoveryState()
        }
    }

    private suspend fun seedPendingRecords() {
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        evidence.getString(KEY_CONFIG_SNAPSHOT, null)?.let { staleSnapshot ->
            restorePreferenceFilesForTest(context, staleSnapshot)
        }
        check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
        val now = backendNowEpochMillis()
        val database = HelmetDatabase.get(context)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val calls = CallStore(database) { now }
        calls.active()?.let { active ->
            require(isOwnedStartupTestCall(active, deviceId)) {
                "a non-test active call prevents startup recovery seeding"
            }
            completeOwnedStartupTestCall(calls, active, now)
        }
        check(calls.active() == null) { "startup recovery test call did not become terminal" }
        val configStore = RuntimeConfigStore(context)
        val configSnapshot = capturePreferenceFilesForTest(context, CONFIG_PREFERENCE_FILES)
        check(evidence.edit().putString(KEY_CONFIG_SNAPSHOT, configSnapshot).commit()) {
            "failed to persist encrypted configuration snapshot"
        }
        try {
        context.stopService(HelmetService.startIntent(context))
        delay(SERVICE_STOP_DELAY_MILLIS)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelAllWork().result.get()
        }
        clearStaleStartupMediaFiles()
        val tracks = TrackStore(database)
        val media = MediaStore(database)
        val alerts = SafetyStore(database)
        val nonce = UUID.randomUUID().toString()
        val trackMessageId = "startup-track-$nonce"
        val mediaId = "startup-media-$nonce"
        val alertMessageId = "startup-alert-message-$nonce"
        val alertId = "startup-alert-$nonce"
        val callId = "startup-call-$nonce"
        val track = requireNotNull(
            tracks.record(
                LocationFix(
                    fixId = "startup-fix-$nonce",
                    deviceId = deviceId,
                    occurredAtEpochMillis = now,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    source = LocationSource.ANDROID_GNSS,
                    quality = FixQuality.STANDARD,
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 12.0,
                    horizontalAccuracyMeters = 2.0f,
                    verticalAccuracyMeters = 3.0f,
                    speedMetersPerSecond = 0.5f,
                    bearingDegrees = 90.0f,
                    gnss = GnssQuality(satellitesUsed = 12, satellitesVisible = 18, hdop = 0.8),
                    provider = "startup-test",
                    isMock = false,
                ),
                trackMessageId,
            ),
        )
        assertEquals(DeliveryState.PENDING, track.deliveryState)

        val mediaDirectory = File(context.filesDir, "media/photo").apply { mkdirs() }
        val mediaFile = File(mediaDirectory, "$mediaId.jpg")
        mediaFile.writeBytes(ByteArray(MEDIA_BYTES) { index -> (index * 31 + nonce.length).toByte() })
        val mediaAsset = MediaAsset(
            assetId = mediaId,
            kind = MediaKind.PHOTO,
            filePath = mediaFile.absolutePath,
            mimeType = "image/jpeg",
            byteSize = mediaFile.length(),
            sha256 = sha256(mediaFile),
            width = 640,
            height = 480,
            durationMillis = null,
            createdAtEpochMillis = now,
            deviceId = deviceId,
            relatedEventId = alertId,
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            latitude = 31.2304,
            longitude = 121.4737,
            horizontalAccuracyMeters = 2.0f,
            locationFixType = "STANDARD",
        )
        assertTrue(
            media.add(mediaAsset),
        )
        primeMediaUpload(mediaAsset, mediaFile)
        assertTrue(
            alerts.recordAlert(
                SafetyAlertRecord(
                    messageId = alertMessageId,
                    alertId = alertId,
                    deviceId = deviceId,
                    alarmType = "NEAR_ELECTRIC",
                    severity = EventSeverity.CRITICAL,
                    active = true,
                    configVersion = 1,
                    sampleReference = 123,
                    monotonicMillis = SystemClock.elapsedRealtime() and 0xFFFF_FFFFL,
                    occurredAtEpochMillis = now,
                    localActions = 7,
                    sensorFaults = 0,
                    simulated = true,
                    sensorSnapshotJson = JSONObject(
                        mapOf("electricFieldMilliVolts" to 900, "startupRecoveryTest" to true),
                    ).toString(),
                    latitude = 31.2304,
                    longitude = 121.4737,
                    horizontalAccuracyMeters = 2.0f,
                    locationFixType = "STANDARD",
                    evidenceAssetId = mediaId,
                    deliveryState = DeliveryState.PENDING,
                    attemptCount = 0,
                ),
            ),
        )
        assertEquals(
            callId,
            calls.createOutgoing(
                deviceId = deviceId,
                relatedEventId = alertId,
                simulated = true,
                callId = callId,
            )?.callId,
        )

        assertEquals(0, requireNotNull(tracks.find(trackMessageId)).attemptCount)
        assertEquals(0, requireNotNull(media.find(mediaId)).attemptCount)
        assertEquals(0, requireNotNull(alerts.findAlert(alertMessageId)).attemptCount)
        assertEquals(0, requireNotNull(calls.find(callId)).attemptCount)
        val testConfig = configStore.update { current ->
            current.copy(
                simulatorEnabled = true,
                backendBaseUrl = TEST_ENDPOINT,
                backendBearerToken = backendBearerToken,
                mqttBrokerUri = "",
                mqttClientCertificateAlias = "",
            )
        }
        check(
            evidence.edit()
                .putBoolean(KEY_READY, true)
                .putInt(KEY_PROCESS_ID, Process.myPid())
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_TRACK_MESSAGE_ID, trackMessageId)
                .putString(KEY_MEDIA_ID, mediaId)
                .putString(KEY_MEDIA_PATH, mediaFile.absolutePath)
                .putString(KEY_ALERT_MESSAGE_ID, alertMessageId)
                .putString(KEY_ALERT_ID, alertId)
                .putString(KEY_CALL_ID, callId)
                .putLong(KEY_TEST_REVISION, testConfig.revision)
                .commit(),
        ) { "failed to persist startup recovery metadata" }
        } catch (error: Throwable) {
            restorePreferenceFilesForTest(context, configSnapshot)
            check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            throw error
        }
    }

    private suspend fun verifyAutomaticRecovery() {
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        val configSnapshot = requireEvidence(evidence, KEY_CONFIG_SNAPSHOT)
        try {
            check(evidence.getBoolean(KEY_READY, false)) { "startup recovery metadata is missing" }
            assertNotEquals(evidence.getInt(KEY_PROCESS_ID, Process.myPid()), Process.myPid())
            val deviceId = requireEvidence(evidence, KEY_DEVICE_ID)
            val trackMessageId = requireEvidence(evidence, KEY_TRACK_MESSAGE_ID)
            val mediaId = requireEvidence(evidence, KEY_MEDIA_ID)
            val mediaPath = requireEvidence(evidence, KEY_MEDIA_PATH)
            val alertMessageId = requireEvidence(evidence, KEY_ALERT_MESSAGE_ID)
            val alertId = requireEvidence(evidence, KEY_ALERT_ID)
            val callId = requireEvidence(evidence, KEY_CALL_ID)
            val testRevision = evidence.getLong(KEY_TEST_REVISION, -1)
            val database = HelmetDatabase.get(context)
            val tracks = TrackStore(database)
            val media = MediaStore(database)
            val alerts = SafetyStore(database)
            val calls = CallStore(database)
            withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                RuntimeStatus.snapshot.first { snapshot -> snapshot.configRevision == testRevision }
            }
            withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                while (
                    tracks.find(trackMessageId)?.deliveryState != DeliveryState.DELIVERED ||
                    media.find(mediaId)?.transferState != MediaTransferState.DELIVERED ||
                    alerts.findAlert(alertMessageId)?.deliveryState != DeliveryState.DELIVERED ||
                    calls.find(callId)?.deliveryState != DeliveryState.DELIVERED
                ) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }

            assertTrue(requireNotNull(tracks.find(trackMessageId)).attemptCount >= 1)
            assertTrue(requireNotNull(media.find(mediaId)).attemptCount >= 1)
            assertTrue(requireNotNull(alerts.findAlert(alertMessageId)).attemptCount >= 1)
            assertTrue(requireNotNull(calls.find(callId)).attemptCount >= 1)
            val uploadEvents = withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                EventStore(database).observeRecent(EVENT_SEARCH_LIMIT).first { events ->
                    events.any { event ->
                        event.eventType == "MEDIA_UPLOAD_COMPLETED" &&
                            JSONObject(event.payloadJson).optString("assetId") == mediaId
                    }
                }
            }
            val uploadEvent = requireNotNull(
                uploadEvents.firstOrNull { event ->
                    event.eventType == "MEDIA_UPLOAD_COMPLETED" &&
                        JSONObject(event.payloadJson).optString("assetId") == mediaId
                },
            )
            assertEquals(
                (MEDIA_BYTES - PRELOADED_MEDIA_BYTES).toLong(),
                JSONObject(uploadEvent.payloadJson).getLong("bytesUploaded"),
            )
            val trackPoints = getJson("/v1/tracks?deviceId=$deviceId&afterSequence=0&limit=100")
                .getJSONArray("points")
            assertTrue(
                (0 until trackPoints.length()).any { index ->
                    trackPoints.getJSONObject(index).getString("messageId") == trackMessageId
                },
            )
            assertEquals(mediaId, getJson("/v1/media/$mediaId").getString("mediaId"))
            assertEquals(alertId, getJson("/v1/alerts/$alertId").getString("alertId"))
            assertEquals(callId, getJson("/v1/calls/$callId").getString("callId"))
            assertTrue(!File(mediaPath).exists() || File(mediaPath).delete())
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    val calls = CallStore(HelmetDatabase.get(context))
                    calls.find(evidence.getString(KEY_CALL_ID, null).orEmpty())
                        ?.takeIf { call -> isOwnedStartupTestCall(call, call.deviceId) }
                        ?.let { call ->
                            completeOwnedStartupTestCall(calls, call, System.currentTimeMillis())
                        }
                }
                context.stopService(HelmetService.startIntent(context))
                delay(SERVICE_STOP_DELAY_MILLIS)
                restorePreferenceFilesForTest(context, configSnapshot)
                check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
                ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            }
        }
    }

    private suspend fun cleanupRecoveryState() = withContext(NonCancellable) {
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        val snapshot = evidence.getString(KEY_CONFIG_SNAPSHOT, null)
        val callId = evidence.getString(KEY_CALL_ID, null)
        context.stopService(HelmetService.startIntent(context))
        delay(SERVICE_STOP_DELAY_MILLIS)
        if (!callId.isNullOrBlank()) {
            val calls = CallStore(HelmetDatabase.get(context))
            calls.find(callId)
                ?.takeIf { call -> isOwnedStartupTestCall(call, call.deviceId) }
                ?.let { call -> completeOwnedStartupTestCall(calls, call, System.currentTimeMillis()) }
        }
        if (!snapshot.isNullOrBlank()) restorePreferenceFilesForTest(context, snapshot)
        check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
        ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
    }

    private suspend fun completeOwnedStartupTestCall(
        calls: CallStore,
        call: CallSession,
        nowEpochMillis: Long,
    ) {
        require(isOwnedStartupTestCall(call, call.deviceId)) { "call is not owned by startup recovery test" }
        if (call.state in TERMINAL_CALL_STATES) return
        check(call.updatedAtEpochMillis < Long.MAX_VALUE) { "test call timeline is exhausted" }
        val supersedePending = call.deliveryState in setOf(
            DeliveryState.PENDING,
            DeliveryState.IN_FLIGHT,
            DeliveryState.FAILED,
        )
        calls.applyRemoteTransition(
            callId = call.callId,
            target = CallState.ENDED,
            stateSequence = if (supersedePending) call.stateSequence else call.stateSequence + 1,
            occurredAtEpochMillis = if (supersedePending) {
                maxOf(nowEpochMillis, call.updatedAtEpochMillis)
            } else {
                maxOf(nowEpochMillis, call.updatedAtEpochMillis + 1)
            },
            reason = STARTUP_TEST_CALL_CLEANUP_REASON,
        )
    }

    private fun isOwnedStartupTestCall(call: CallSession, expectedDeviceId: String): Boolean =
        call.callId.startsWith(STARTUP_CALL_PREFIX) &&
            call.deviceId == expectedDeviceId &&
            call.direction == CallDirection.OUTGOING_DEVICE &&
            call.simulated &&
            call.relatedEventId?.startsWith(STARTUP_ALERT_PREFIX) == true

    private fun clearStaleStartupMediaFiles() {
        File(context.filesDir, "media/photo").listFiles()
            ?.filter { file -> file.name.startsWith("startup-media-") && file.name.endsWith(".jpg") }
            ?.forEach { file -> check(file.delete()) { "failed to delete stale test media ${file.name}" } }
    }

    private suspend fun primeMediaUpload(asset: MediaAsset, file: File) = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("mediaId", asset.assetId)
            .put("deviceId", asset.deviceId)
            .put("kind", asset.kind.name)
            .put("mimeType", asset.mimeType)
            .put("byteSize", asset.byteSize)
            .put("sha256", asset.sha256)
            .put("createdAtEpochMillis", asset.createdAtEpochMillis)
            .put("relatedEventId", asset.relatedEventId ?: JSONObject.NULL)
            .put("width", asset.width)
            .put("height", asset.height)
            .put("durationMillis", asset.durationMillis ?: JSONObject.NULL)
            .put("personId", asset.personId ?: JSONObject.NULL)
            .put(
                "location",
                JSONObject()
                    .put("latitude", asset.latitude ?: JSONObject.NULL)
                    .put("longitude", asset.longitude ?: JSONObject.NULL)
                    .put(
                        "horizontalAccuracyMeters",
                        asset.horizontalAccuracyMeters ?: JSONObject.NULL,
                    )
                    .put("fixType", asset.locationFixType),
            )
        val session = requestJson(
            method = "POST",
            path = "/v1/media/sessions",
            body = metadata.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json; charset=utf-8",
        )
        assertEquals(0L, session.getLong("nextOffset"))
        assertEquals(PRELOADED_MEDIA_BYTES, session.getInt("chunkSize"))
        val firstChunk = file.readBytes().copyOfRange(0, PRELOADED_MEDIA_BYTES)
        assertEquals(PRELOADED_MEDIA_BYTES, firstChunk.size)
        val sessionId = URLEncoder.encode(session.getString("sessionId"), Charsets.UTF_8.name())
            .replace("+", "%20")
        val response = requestJson(
            method = "PUT",
            path = "/v1/media/sessions/$sessionId/chunks",
            body = firstChunk,
            contentType = "application/octet-stream",
            headers = mapOf(
                "Content-Range" to "bytes 0-${PRELOADED_MEDIA_BYTES - 1}/${asset.byteSize}",
                "X-Chunk-SHA256" to sha256(firstChunk),
            ),
        )
        assertEquals(PRELOADED_MEDIA_BYTES.toLong(), response.getLong("nextOffset"))
    }

    private fun requestJson(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
            setRequestProperty("Content-Type", contentType)
            headers.forEach(::setRequestProperty)
        }
        return try {
            connection.outputStream.use { output -> output.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { reader -> reader.readText() }
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
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
            setRequestProperty("X-Actor-Id", "startup-recovery-board-test")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { reader -> reader.readText() }
            check(status in 200..299) { "backend HTTP $status: $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun backendNowEpochMillis(): Long = withContext(Dispatchers.IO) {
        val connection = URL("$TEST_ENDPOINT/ready").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            val status = connection.responseCode
            check(status in 200..299) { "backend readiness HTTP $status" }
            connection.inputStream.use { input -> input.readBytes() }
            connection.getHeaderFieldDate("Date", -1L).also { serverTime ->
                check(serverTime > 0) { "backend response is missing a valid Date header" }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String =
        sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun requireEvidence(preferences: android.content.SharedPreferences, key: String): String =
        requireNotNull(preferences.getString(key, null)?.takeIf(String::isNotBlank)) { "$key is missing" }

    companion object {
        private const val PHASE_ARGUMENT = "automaticStartupRecoveryPhase"
        private const val PHASE_SEED = "seed"
        private const val PHASE_RECOVER = "recover"
        private const val PHASE_CLEANUP = "cleanup"
        private const val EVIDENCE_PREFERENCES = "automatic_startup_recovery_evidence"
        private const val KEY_READY = "ready"
        private const val KEY_PROCESS_ID = "process_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRACK_MESSAGE_ID = "track_message_id"
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_MEDIA_PATH = "media_path"
        private const val KEY_ALERT_MESSAGE_ID = "alert_message_id"
        private const val KEY_ALERT_ID = "alert_id"
        private const val KEY_CALL_ID = "call_id"
        private const val KEY_TEST_REVISION = "test_revision"
        private const val KEY_CONFIG_SNAPSHOT = "encrypted_config_snapshot"
        private const val BACKEND_BEARER_TOKEN_ARGUMENT = "backendBearerToken"
        private const val TEST_ENDPOINT = "http://127.0.0.1:18084"
        private const val PRELOADED_MEDIA_BYTES = 64 * 1024
        private const val MEDIA_BYTES = 4 * PRELOADED_MEDIA_BYTES
        private const val EVENT_SEARCH_LIMIT = 200
        private const val SERVICE_STOP_DELAY_MILLIS = 750L
        private const val RECOVERY_TIMEOUT_MILLIS = 45_000L
        private const val POLL_INTERVAL_MILLIS = 200L
        private const val STARTUP_CALL_PREFIX = "startup-call-"
        private const val STARTUP_ALERT_PREFIX = "startup-alert-"
        private const val STARTUP_TEST_CALL_CLEANUP_REASON = "STARTUP_RECOVERY_TEST_CLEANUP"
        private val TERMINAL_CALL_STATES = setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)
        private val CONFIG_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
        )
    }
}
