package com.example.helmet.service.runtime

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.data.local.TrackStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadBatchContinuationInstrumentedTest {
    @Test
    fun workManagerUploadsEveryTrackAlertAndCallBatch() = runBlocking {
        assumeTrue(
            "explicit batch continuation phase is required",
            InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT) == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val database = HelmetDatabase.get(context)
        val trackStore = TrackStore(database)
        val safetyStore = SafetyStore(database)
        val callStore = CallStore(database)
        val workManager = WorkManager.getInstance(context)
        val trackRoute = backendWorkRoute(TRACK_WORK_BASE_NAME, LOOPBACK_ENDPOINT)
        val alertRoute = backendWorkRoute(ALERT_WORK_BASE_NAME, LOOPBACK_ENDPOINT)
        val communicationRoute = backendWorkRoute(COMMUNICATION_WORK_BASE_NAME, LOOPBACK_ENDPOINT)
        val previousTrackWorkIds = workManager.workInfos(trackRoute.activeName).map { item -> item.id }.toSet()
        val previousAlertWorkIds = workManager.workInfos(alertRoute.activeName).map { item -> item.id }.toSet()
        val previousCommunicationWorkIds = workManager.workInfos(communicationRoute.activeName)
            .map { item -> item.id }
            .toSet()
        workManager.cancelUniqueWork(trackRoute.activeName).result.await()
        workManager.cancelUniqueWork(alertRoute.activeName).result.await()
        workManager.cancelUniqueWork(communicationRoute.activeName).result.await()

        val nonce = UUID.randomUUID().toString()
        val deviceId = "batch-device-$nonce"
        val now = System.currentTimeMillis()
        val trackMessageIds = (0 until TRACK_COUNT).map { index ->
            val messageId = "batch-track-$nonce-$index"
            val point = trackStore.record(
                LocationFix(
                    fixId = "batch-fix-$nonce-$index",
                    deviceId = deviceId,
                    occurredAtEpochMillis = now + index,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + index,
                    source = LocationSource.ANDROID_GNSS,
                    quality = FixQuality.STANDARD,
                    latitude = 31.2304 + index * 0.000001,
                    longitude = 121.4737 + index * 0.000001,
                    altitudeMeters = 12.5,
                    horizontalAccuracyMeters = 2.0f,
                    verticalAccuracyMeters = 3.0f,
                    speedMetersPerSecond = 1.0f,
                    bearingDegrees = 45.0f,
                    gnss = GnssQuality(satellitesUsed = 12, satellitesVisible = 18, hdop = 0.8),
                    provider = "gps",
                    isMock = false,
                ),
                messageId,
            )
            assertEquals(messageId, point?.messageId)
            messageId
        }
        val alertMessageIds = (0 until ALERT_COUNT).map { index ->
            val messageId = "batch-alert-message-$nonce-$index"
            assertTrue(
                safetyStore.recordAlert(
                    SafetyAlertRecord(
                        messageId = messageId,
                        alertId = "batch-alert-$nonce-$index",
                        deviceId = deviceId,
                        alarmType = "NEAR_ELECTRIC",
                        severity = EventSeverity.CRITICAL,
                        active = true,
                        configVersion = 3,
                        sampleReference = index.toLong() + 1,
                        monotonicMillis = index.toLong() + 1,
                        occurredAtEpochMillis = now + index,
                        localActions = 7,
                        sensorFaults = 0,
                        simulated = true,
                        sensorSnapshotJson = JSONObject(
                            mapOf("electricFieldMilliVolts" to 920 + index),
                        ).toString(),
                        latitude = 31.2304,
                        longitude = 121.4737,
                        horizontalAccuracyMeters = 2.0f,
                        locationFixType = "STANDARD",
                        evidenceAssetId = null,
                        deliveryState = DeliveryState.PENDING,
                        attemptCount = 0,
                    ),
                ),
            )
            messageId
        }
        val callIds = (0 until CALL_COUNT).map { index ->
            val callId = "batch-call-$nonce-$index"
            assertEquals(
                callId,
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = null,
                    simulated = true,
                    callId = callId,
                )?.callId,
            )
            callId
        }

        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = LOOPBACK_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            TrackUploadWorker.enqueue(context)
            SafetyAlertWorker.enqueue(context)
            CommunicationWorker.enqueue(context)

            withTimeout(WORK_TIMEOUT_MILLIS) {
                while (
                    trackStore.find(trackMessageIds.last())?.deliveryState != DeliveryState.DELIVERED ||
                    safetyStore.findAlert(alertMessageIds.last())?.deliveryState != DeliveryState.DELIVERED ||
                    callStore.find(callIds.last())?.deliveryState != DeliveryState.DELIVERED
                ) {
                    delay(WORK_POLL_MILLIS)
                }
            }

            trackMessageIds.forEach { messageId ->
                assertEquals(DeliveryState.DELIVERED, trackStore.find(messageId)?.deliveryState)
                assertTrue(requireNotNull(trackStore.find(messageId)).attemptCount >= 1)
            }
            alertMessageIds.forEach { messageId ->
                assertEquals(DeliveryState.DELIVERED, safetyStore.findAlert(messageId)?.deliveryState)
                assertTrue(requireNotNull(safetyStore.findAlert(messageId)).attemptCount >= 1)
            }
            callIds.forEach { callId ->
                assertEquals(DeliveryState.DELIVERED, callStore.find(callId)?.deliveryState)
                assertTrue(requireNotNull(callStore.find(callId)).attemptCount >= 1)
            }
            assertCompletedContinuation(workManager, trackRoute.activeName, previousTrackWorkIds)
            assertCompletedContinuation(workManager, alertRoute.activeName, previousAlertWorkIds)
            assertCompletedContinuation(
                workManager,
                communicationRoute.activeName,
                previousCommunicationWorkIds,
            )
        } finally {
            configStore.save(originalConfig)
            workManager.cancelUniqueWork(trackRoute.activeName).result.await()
            workManager.cancelUniqueWork(alertRoute.activeName).result.await()
            workManager.cancelUniqueWork(communicationRoute.activeName).result.await()
        }
    }

    private suspend fun assertCompletedContinuation(
        workManager: WorkManager,
        workName: String,
        previousWorkIds: Set<UUID>,
    ) {
        withTimeout(WORK_TIMEOUT_MILLIS) {
            var work = workManager.workInfos(workName).filterNot { item -> item.id in previousWorkIds }
            while (work.size < 2 || work.any { item -> !item.state.isFinished }) {
                delay(WORK_POLL_MILLIS)
                work = workManager.workInfos(workName).filterNot { item -> item.id in previousWorkIds }
            }
            assertTrue(work.all { item -> item.state == WorkInfo.State.SUCCEEDED })
        }
    }

    private suspend fun WorkManager.workInfos(name: String): List<WorkInfo> =
        withContext(Dispatchers.IO) { getWorkInfosForUniqueWork(name).get() }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        withContext(Dispatchers.IO) { get() }

    companion object {
        private const val PHASE_ARGUMENT = "batchContinuation"
        private const val TRACK_WORK_BASE_NAME = "helmet-track-upload"
        private const val ALERT_WORK_BASE_NAME = "helmet-safety-alert-upload"
        private const val COMMUNICATION_WORK_BASE_NAME = "helmet-communication-sync"
        private const val LOOPBACK_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
        private const val TRACK_COUNT = 201
        private const val ALERT_COUNT = 101
        private const val CALL_COUNT = 101
        private const val WORK_TIMEOUT_MILLIS = 60_000L
        private const val WORK_POLL_MILLIS = 50L
    }
}
