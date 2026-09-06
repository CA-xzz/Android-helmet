package com.example.helmet.service.runtime

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
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
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.media.sync.MediaIntegrity
import java.io.File
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableOfflineRecoveryInstrumentedTest {
    @Test
    fun durableUploadsRecoverAfterProcessRestart() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue("explicit enqueue or recover phase is required", phase in setOf(PHASE_ENQUEUE, PHASE_RECOVER))
        val backendBearerToken = requireBoardBackendBearerToken(
            arguments.getString(BACKEND_BEARER_TOKEN_ARGUMENT),
        )
        when (phase) {
            PHASE_ENQUEUE -> enqueueWhileBackendIsUnavailable(backendBearerToken)
            PHASE_RECOVER -> recoverAfterProcessRestart(backendBearerToken)
        }
    }

    private suspend fun enqueueWhileBackendIsUnavailable(backendBearerToken: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        evidence.getString(KEY_CONFIG_SNAPSHOT, null)?.let { staleSnapshot ->
            restorePreferenceFilesForRecoveryTest(context, staleSnapshot)
            check(evidence.edit().clear().commit()) { "failed to clear stale recovery metadata" }
        }
        check(!evidence.getBoolean(KEY_READY, false)) {
            "a previous recovery phase is incomplete; reinstall the test package"
        }
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val configSnapshot = capturePreferenceFilesForRecoveryTest(context, CONFIG_PREFERENCE_FILES)
        check(evidence.edit().putString(KEY_CONFIG_SNAPSHOT, configSnapshot).commit()) {
            "failed to persist encrypted configuration snapshot"
        }
        try {
        val database = HelmetDatabase.get(context)
        val trackStore = TrackStore(database)
        val mediaStore = MediaStore(database)
        val safetyStore = SafetyStore(database)
        val nonce = UUID.randomUUID().toString()
        val deviceId = "board-recovery-$nonce"
        val trackMessageId = "recovery-track-$nonce"
        val mediaId = "recovery-media-$nonce"
        val alertMessageId = "recovery-alert-message-$nonce"
        val alertId = "recovery-alert-$nonce"
        val now = System.currentTimeMillis()
        val track = requireNotNull(
            trackStore.record(
                LocationFix(
                    fixId = "recovery-fix-$nonce",
                    deviceId = deviceId,
                    occurredAtEpochMillis = now,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    source = LocationSource.ANDROID_GNSS,
                    quality = FixQuality.STANDARD,
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 12.5,
                    horizontalAccuracyMeters = 2.0f,
                    verticalAccuracyMeters = 3.0f,
                    speedMetersPerSecond = 1.0f,
                    bearingDegrees = 45.0f,
                    gnss = GnssQuality(satellitesUsed = 12, satellitesVisible = 18, hdop = 0.8),
                    provider = "gps",
                    isMock = false,
                ),
                trackMessageId,
            ),
        )
        val mediaDirectory = File(context.filesDir, "media/video").apply { mkdirs() }
        val mediaFile = File(mediaDirectory, "$mediaId.mp4")
        val seed = nonce.toByteArray()
        mediaFile.writeBytes(
            ByteArray(MEDIA_BYTES) { index ->
                ((index % 239) xor seed[index % seed.size].toInt()).toByte()
            },
        )
        val media = MediaAsset(
            assetId = mediaId,
            kind = MediaKind.VIDEO,
            filePath = mediaFile.absolutePath,
            mimeType = "video/mp4",
            byteSize = mediaFile.length(),
            sha256 = MediaIntegrity.sha256(mediaFile),
            width = 1_920,
            height = 1_080,
            durationMillis = 2_000,
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
        assertTrue(mediaStore.add(media))
        val alert = SafetyAlertRecord(
            messageId = alertMessageId,
            alertId = alertId,
            deviceId = deviceId,
            alarmType = "NEAR_ELECTRIC",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 3,
            sampleReference = 77,
            monotonicMillis = 12_345,
            occurredAtEpochMillis = now,
            localActions = 2,
            sensorFaults = 0,
            simulated = true,
            sensorSnapshotJson = JSONObject(
                mapOf(
                    "electricFieldMilliVolts" to 920,
                    "electricFieldBaselineMilliVolts" to 100,
                    "electricFieldExposureMilliVoltMillis" to 82_000,
                ),
            ).toString(),
            latitude = 31.2304,
            longitude = 121.4737,
            horizontalAccuracyMeters = 2.0f,
            locationFixType = "STANDARD",
            evidenceAssetId = mediaId,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(safetyStore.recordAlert(alert))

        configStore.saveForInstrumentationTest(
                originalConfig.copy(
                    backendBaseUrl = OFFLINE_ENDPOINT,
                    backendBearerToken = backendBearerToken,
            ),
        )
        assertWorkerResult<ListenableWorker.Result.Retry, TrackUploadWorker>(context)
        assertWorkerResult<ListenableWorker.Result.Retry, MediaUploadWorker>(context)
        assertWorkerResult<ListenableWorker.Result.Retry, SafetyAlertWorker>(context)
        assertEquals(DeliveryState.FAILED, trackStore.find(track.messageId)?.deliveryState)
        assertEquals(1, trackStore.find(track.messageId)?.attemptCount)
        assertEquals(MediaTransferState.FAILED, mediaStore.find(mediaId)?.transferState)
        assertEquals(1, mediaStore.find(mediaId)?.attemptCount)
        assertEquals(DeliveryState.FAILED, safetyStore.findAlert(alertMessageId)?.deliveryState)
        assertEquals(1, safetyStore.findAlert(alertMessageId)?.attemptCount)

        configStore.saveForInstrumentationTest(
                originalConfig.copy(
                    backendBaseUrl = STALE_NETWORK_ENDPOINT,
                    backendBearerToken = backendBearerToken,
            ),
        )
        TrackUploadWorker.enqueue(context)
        MediaUploadWorker.enqueue(context)
        SafetyAlertWorker.enqueue(context)
        awaitStaleNetworkWork(context)

        check(
            evidence.edit()
                .putBoolean(KEY_READY, true)
                .putInt(KEY_PROCESS_ID, Process.myPid())
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_TRACK_MESSAGE_ID, trackMessageId)
                .putString(KEY_MEDIA_ID, mediaId)
                .putString(KEY_ALERT_MESSAGE_ID, alertMessageId)
                .putString(KEY_ALERT_ID, alertId)
                .putString(KEY_MEDIA_PATH, mediaFile.absolutePath)
                .commit(),
        ) { "failed to persist recovery phase metadata" }
        } catch (error: Throwable) {
            restorePreferenceFilesForRecoveryTest(context, configSnapshot)
            check(evidence.edit().clear().commit()) { "failed to clear recovery phase metadata" }
            throw error
        }
    }

    private suspend fun recoverAfterProcessRestart(backendBearerToken: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)
        val configSnapshot = requireEvidence(evidence.getString(KEY_CONFIG_SNAPSHOT, null), KEY_CONFIG_SNAPSHOT)
        try {
            check(evidence.getBoolean(KEY_READY, false)) { "enqueue phase metadata is missing" }
            assertNotEquals(evidence.getInt(KEY_PROCESS_ID, Process.myPid()), Process.myPid())
            val deviceId = requireEvidence(evidence.getString(KEY_DEVICE_ID, null), KEY_DEVICE_ID)
            val trackMessageId = requireEvidence(
                evidence.getString(KEY_TRACK_MESSAGE_ID, null),
                KEY_TRACK_MESSAGE_ID,
            )
            val mediaId = requireEvidence(evidence.getString(KEY_MEDIA_ID, null), KEY_MEDIA_ID)
            val alertMessageId = requireEvidence(
                evidence.getString(KEY_ALERT_MESSAGE_ID, null),
                KEY_ALERT_MESSAGE_ID,
            )
            val alertId = requireEvidence(evidence.getString(KEY_ALERT_ID, null), KEY_ALERT_ID)
            val mediaPath = requireEvidence(evidence.getString(KEY_MEDIA_PATH, null), KEY_MEDIA_PATH)
            val configStore = RuntimeConfigStore(context)
            val offlineConfig = configStore.load()
            val database = HelmetDatabase.get(context)
            val trackStore = TrackStore(database)
            val mediaStore = MediaStore(database)
            val safetyStore = SafetyStore(database)
            assertEquals(DeliveryState.FAILED, trackStore.find(trackMessageId)?.deliveryState)
            assertEquals(1, trackStore.find(trackMessageId)?.attemptCount)
            assertEquals(MediaTransferState.FAILED, mediaStore.find(mediaId)?.transferState)
            assertEquals(1, mediaStore.find(mediaId)?.attemptCount)
            assertEquals(DeliveryState.FAILED, safetyStore.findAlert(alertMessageId)?.deliveryState)
            assertEquals(1, safetyStore.findAlert(alertMessageId)?.attemptCount)
            assertTrue(File(mediaPath).isFile)

            configStore.saveForInstrumentationTest(
                offlineConfig.copy(
                    backendBaseUrl = ONLINE_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                ),
            )
            TrackUploadWorker.enqueue(context)
            MediaUploadWorker.enqueue(context)
            SafetyAlertWorker.enqueue(context)
            awaitDelivered(trackStore, mediaStore, safetyStore, trackMessageId, mediaId, alertMessageId)
            assertEquals(DeliveryState.DELIVERED, trackStore.find(trackMessageId)?.deliveryState)
            assertEquals(2, trackStore.find(trackMessageId)?.attemptCount)
            assertEquals(MediaTransferState.DELIVERED, mediaStore.find(mediaId)?.transferState)
            assertEquals(2, mediaStore.find(mediaId)?.attemptCount)
            assertEquals(DeliveryState.DELIVERED, safetyStore.findAlert(alertMessageId)?.deliveryState)
            assertEquals(2, safetyStore.findAlert(alertMessageId)?.attemptCount)

            val tracks = getJson(
                "/v1/tracks?deviceId=$deviceId&afterSequence=0&limit=10",
                backendBearerToken,
            )
                .getJSONArray("points")
            assertTrue((0 until tracks.length()).any { tracks.getJSONObject(it).getString("messageId") == trackMessageId })
            val storedAlert = getJson("/v1/alerts/$alertId", backendBearerToken)
            assertEquals(alertId, storedAlert.getString("alertId"))
            assertEquals(alertMessageId, storedAlert.getJSONObject("evidence").getString("relatedEventId"))
            assertEquals(mediaId, getJson("/v1/media/$mediaId", backendBearerToken).getString("mediaId"))

            assertWorkerResult<ListenableWorker.Result.Success, TrackUploadWorker>(context)
            assertWorkerResult<ListenableWorker.Result.Success, MediaUploadWorker>(context)
            assertWorkerResult<ListenableWorker.Result.Success, SafetyAlertWorker>(context)
            assertEquals(2, trackStore.find(trackMessageId)?.attemptCount)
            assertEquals(2, mediaStore.find(mediaId)?.attemptCount)
            assertEquals(2, safetyStore.findAlert(alertMessageId)?.attemptCount)
            assertRoutesReconciled(context)
            assertTrue(File(mediaPath).delete())
        } finally {
            restorePreferenceFilesForRecoveryTest(context, configSnapshot)
            check(evidence.edit().clear().commit()) { "failed to clear recovery phase metadata" }
        }
    }

    private suspend fun awaitStaleNetworkWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        withTimeout(WORK_TIMEOUT_MILLIS) {
            ROUTED_WORK_BASE_NAMES.forEach { baseName ->
                val workName = backendWorkRoute(baseName, STALE_NETWORK_ENDPOINT).activeName
                var work = workManager.workInfos(workName)
                while (work.isEmpty()) {
                    delay(WORK_POLL_MILLIS)
                    work = workManager.workInfos(workName)
                }
                assertTrue(work.all { item -> item.state == WorkInfo.State.ENQUEUED })
            }
        }
    }

    private suspend fun awaitDelivered(
        trackStore: TrackStore,
        mediaStore: MediaStore,
        safetyStore: SafetyStore,
        trackMessageId: String,
        mediaId: String,
        alertMessageId: String,
    ) {
        withTimeout(WORK_TIMEOUT_MILLIS) {
            while (
                trackStore.find(trackMessageId)?.deliveryState != DeliveryState.DELIVERED ||
                mediaStore.find(mediaId)?.transferState != MediaTransferState.DELIVERED ||
                safetyStore.findAlert(alertMessageId)?.deliveryState != DeliveryState.DELIVERED
            ) {
                delay(WORK_POLL_MILLIS)
            }
        }
    }

    private suspend fun assertRoutesReconciled(context: Context) {
        val workManager = WorkManager.getInstance(context)
        ROUTED_WORK_BASE_NAMES.forEach { baseName ->
            val staleName = backendWorkRoute(baseName, STALE_NETWORK_ENDPOINT).activeName
            val activeName = backendWorkRoute(baseName, ONLINE_ENDPOINT).activeName
            val stale = workManager.workInfos(staleName)
            val active = workManager.workInfos(activeName)
            assertTrue(stale.isNotEmpty())
            assertTrue(stale.all { item -> item.state == WorkInfo.State.CANCELLED })
            assertTrue(active.any { item -> item.state == WorkInfo.State.SUCCEEDED })
        }
    }

    private suspend fun WorkManager.workInfos(name: String): List<WorkInfo> =
        withContext(Dispatchers.IO) { getWorkInfosForUniqueWork(name).get() }

    private suspend inline fun <reified ResultType : ListenableWorker.Result, reified WorkerType : CoroutineWorker>
        assertWorkerResult(context: Context) {
        val result = TestListenableWorkerBuilder<WorkerType>(context).build().doWork()
        assertEquals(ResultType::class.java, result.javaClass)
    }

    private suspend fun getJson(path: String, backendBearerToken: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(ONLINE_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
            setRequestProperty("X-Actor-Id", "durable-recovery-host")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
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

    private fun requireEvidence(value: String?, key: String): String =
        requireNotNull(value?.takeIf(String::isNotBlank)) { "$key is missing" }

    companion object {
        private const val PHASE_ARGUMENT = "durableRecoveryPhase"
        private const val PHASE_ENQUEUE = "enqueue"
        private const val PHASE_RECOVER = "recover"
        private const val EVIDENCE_PREFERENCES = "durable_offline_recovery_evidence"
        private const val KEY_READY = "ready"
        private const val KEY_PROCESS_ID = "process_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRACK_MESSAGE_ID = "track_message_id"
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_ALERT_MESSAGE_ID = "alert_message_id"
        private const val KEY_ALERT_ID = "alert_id"
        private const val KEY_MEDIA_PATH = "media_path"
        private const val KEY_CONFIG_SNAPSHOT = "encrypted_config_snapshot"
        private const val OFFLINE_ENDPOINT = "http://127.0.0.1:1"
        private const val STALE_NETWORK_ENDPOINT = "https://unreachable.invalid"
        private const val ONLINE_ENDPOINT = "http://127.0.0.1:18080"
        private const val MEDIA_BYTES = 300_000
        private const val WORK_TIMEOUT_MILLIS = 30_000L
        private const val WORK_POLL_MILLIS = 100L
        private val ROUTED_WORK_BASE_NAMES = listOf(
            "helmet-track-upload",
            "helmet-media-upload",
            "helmet-safety-alert-upload",
        )
        private val CONFIG_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
        )
    }
}
