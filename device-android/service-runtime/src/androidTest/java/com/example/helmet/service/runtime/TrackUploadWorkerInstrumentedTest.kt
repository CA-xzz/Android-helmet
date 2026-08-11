package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.TrackStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackUploadWorkerInstrumentedTest {
    @Test
    fun workerUploadsOrderedPendingTrackAndMarksItDelivered() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val trackStore = TrackStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val deviceId = "board-track-$nonce"
        val first = requireNotNull(
            trackStore.record(fix(deviceId, "fix-1-$nonce", 1_786_000_000_001), "track-1-$nonce"),
        )
        val second = requireNotNull(
            trackStore.record(fix(deviceId, "fix-2-$nonce", 1_786_000_001_001), "track-2-$nonce"),
        )
        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            val worker = TestListenableWorkerBuilder<TrackUploadWorker>(context).build()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertEquals(DeliveryState.DELIVERED, trackStore.find(first.messageId)?.deliveryState)
            assertEquals(DeliveryState.DELIVERED, trackStore.find(second.messageId)?.deliveryState)
            assertEquals(1, trackStore.find(first.messageId)?.attemptCount)
            assertEquals(1, trackStore.find(second.messageId)?.attemptCount)

            val repeated = TestListenableWorkerBuilder<TrackUploadWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, repeated.javaClass)
            assertEquals(1, trackStore.find(first.messageId)?.attemptCount)
            assertEquals(1, trackStore.find(second.messageId)?.attemptCount)
        } finally {
            configStore.save(originalConfig)
        }
    }

    private fun fix(deviceId: String, fixId: String, time: Long) = LocationFix(
        fixId = fixId,
        deviceId = deviceId,
        occurredAtEpochMillis = time,
        elapsedRealtimeNanos = time * 1_000,
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
    )

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
    }
}
