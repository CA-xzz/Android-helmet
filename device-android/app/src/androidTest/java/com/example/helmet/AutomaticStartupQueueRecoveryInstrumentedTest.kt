package com.example.helmet

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
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
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.RuntimeStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

@RunWith(AndroidJUnit4::class)
class AutomaticStartupQueueRecoveryInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationStartupRecoversEveryDurableQueue() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue("seed or recover phase is required", phase in setOf(PHASE_SEED, PHASE_RECOVER))
        when (phase) {
            PHASE_SEED -> seedPendingRecords()
            PHASE_RECOVER -> verifyAutomaticRecovery()
        }
    }

    private suspend fun seedPendingRecords() {
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
        context.stopService(HelmetService.startIntent(context))
        delay(SERVICE_STOP_DELAY_MILLIS)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelAllWork().result.get()
        }

        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val testConfig = originalConfig.copy(
            revision = originalConfig.revision + 1,
            simulatorEnabled = true,
            backendBaseUrl = TEST_ENDPOINT,
            backendBearerToken = TEST_TOKEN,
            mqttBrokerUri = "",
            mqttClientCertificateAlias = "",
        )
        val database = HelmetDatabase.get(context)
        val tracks = TrackStore(database)
        val media = MediaStore(database)
        val alerts = SafetyStore(database)
        val calls = CallStore(database)
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val nonce = UUID.randomUUID().toString()
        val trackMessageId = "startup-track-$nonce"
        val mediaId = "startup-media-$nonce"
        val alertMessageId = "startup-alert-message-$nonce"
        val alertId = "startup-alert-$nonce"
        val callId = "startup-call-$nonce"
        val now = System.currentTimeMillis()

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
        assertTrue(
            media.add(
                MediaAsset(
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
                ),
            ),
        )
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
        configStore.save(testConfig)
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
                .putLong(KEY_ORIGINAL_REVISION, originalConfig.revision)
                .putBoolean(KEY_ORIGINAL_SIMULATOR, originalConfig.simulatorEnabled)
                .putString(KEY_ORIGINAL_BASE_URL, originalConfig.backendBaseUrl)
                .putString(KEY_ORIGINAL_TOKEN, originalConfig.backendBearerToken)
                .putString(KEY_ORIGINAL_MQTT_URI, originalConfig.mqttBrokerUri)
                .putString(KEY_ORIGINAL_MQTT_ALIAS, originalConfig.mqttClientCertificateAlias)
                .commit(),
        ) { "failed to persist startup recovery metadata" }
    }

    private suspend fun verifyAutomaticRecovery() {
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
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
        val configStore = RuntimeConfigStore(context)
        val activeConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val tracks = TrackStore(database)
        val media = MediaStore(database)
        val alerts = SafetyStore(database)
        val calls = CallStore(database)
        val originalRevision = evidence.getLong(KEY_ORIGINAL_REVISION, activeConfig.revision)
        val originalSimulator = evidence.getBoolean(KEY_ORIGINAL_SIMULATOR, true)
        val originalBaseUrl = evidence.getString(KEY_ORIGINAL_BASE_URL, "").orEmpty()
        val originalToken = evidence.getString(KEY_ORIGINAL_TOKEN, "").orEmpty()
        val originalMqttUri = evidence.getString(KEY_ORIGINAL_MQTT_URI, "").orEmpty()
        val originalMqttAlias = evidence.getString(KEY_ORIGINAL_MQTT_ALIAS, "").orEmpty()
        var completed = false

        try {
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
            completed = true
        } finally {
            configStore.save(
                activeConfig.copy(
                    revision = originalRevision,
                    simulatorEnabled = originalSimulator,
                    backendBaseUrl = originalBaseUrl,
                    backendBearerToken = originalToken,
                    mqttBrokerUri = originalMqttUri,
                    mqttClientCertificateAlias = originalMqttAlias,
                ),
            )
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_STOP_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            if (completed) {
                check(evidence.edit().clear().commit()) { "failed to clear startup recovery metadata" }
            }
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
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

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun requireEvidence(preferences: android.content.SharedPreferences, key: String): String =
        requireNotNull(preferences.getString(key, null)?.takeIf(String::isNotBlank)) { "$key is missing" }

    companion object {
        private const val PHASE_ARGUMENT = "automaticStartupRecoveryPhase"
        private const val PHASE_SEED = "seed"
        private const val PHASE_RECOVER = "recover"
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
        private const val KEY_ORIGINAL_REVISION = "original_revision"
        private const val KEY_ORIGINAL_SIMULATOR = "original_simulator"
        private const val KEY_ORIGINAL_BASE_URL = "original_base_url"
        private const val KEY_ORIGINAL_TOKEN = "original_token"
        private const val KEY_ORIGINAL_MQTT_URI = "original_mqtt_uri"
        private const val KEY_ORIGINAL_MQTT_ALIAS = "original_mqtt_alias"
        private const val TEST_ENDPOINT = "http://127.0.0.1:18084"
        private const val TEST_TOKEN = "startup-recovery-board-token"
        private const val MEDIA_BYTES = 64 * 1024
        private const val SERVICE_STOP_DELAY_MILLIS = 750L
        private const val RECOVERY_TIMEOUT_MILLIS = 45_000L
        private const val POLL_INTERVAL_MILLIS = 200L
    }
}
