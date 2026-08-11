package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStoreInstrumentedTest {
    private lateinit var database: HelmetDatabase
    private lateinit var store: SafetyStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            HelmetDatabase::class.java,
        ).build()
        store = SafetyStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sampleAndAlertAreIdempotentAndRetainEvidenceFields() = runBlocking {
        val sample = SafetySensorTelemetry(
            sampleId = "device:7",
            deviceId = "device",
            sampleReference = 7,
            monotonicMillis = 900,
            validFlags = 0x0007,
            accelerationXMilliG = -10,
            accelerationYMilliG = 20,
            accelerationZMilliG = 980,
            gyroXMilliDegreesPerSecond = 1,
            gyroYMilliDegreesPerSecond = 2,
            gyroZMilliDegreesPerSecond = 3,
            electricFieldMilliVolts = 250,
            pressurePascals = 101_325,
            temperatureCentiCelsius = null,
            altitudeMillimetres = 2_100,
            simulated = false,
            recordedAtEpochMillis = 1_000,
        )
        assertTrue(store.recordSample(sample))
        assertFalse(store.recordSample(sample))
        assertFalse(store.recordSample(sample.copy(recordedAtEpochMillis = 1_100)))
        assertEquals(sample, store.findSample("device", 7))
        assertEquals(1, store.sampleCount("device"))

        val afterModuleRestart = sample.copy(
            monotonicMillis = 10,
            electricFieldMilliVolts = 100,
            recordedAtEpochMillis = 2_000,
        )
        assertTrue(store.recordSample(afterModuleRestart))
        assertEquals(afterModuleRestart, store.findSample("device", 7))
        assertEquals(1, store.sampleCount("device"))

        val alert = SafetyAlertRecord(
            messageId = "device:alert-1:active",
            alertId = "device:alert-1",
            deviceId = "device",
            alarmType = "NEAR_ELECTRIC",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 2,
            sampleReference = 7,
            monotonicMillis = 900,
            occurredAtEpochMillis = 1_000,
            localActions = 7,
            sensorFaults = 0,
            simulated = false,
            sensorSnapshotJson = "{\"electricFieldMilliVolts\":250}",
            latitude = 30.0,
            longitude = 114.0,
            horizontalAccuracyMeters = 2f,
            locationFixType = "STANDARD",
            evidenceAssetId = "photo-1",
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(store.recordAlert(alert))
        assertFalse(store.recordAlert(alert))
        assertEquals(1, store.pendingAlertCount())
        assertEquals(alert, store.findAlert(alert.messageId))
        assertTrue(store.markAttempt(alert.messageId, 1_100))
        assertEquals(1, store.pendingAlerts().single().attemptCount)
        assertTrue(store.markDelivered(alert.messageId, 1_200))
        assertEquals(0, store.pendingAlertCount())
        assertNotNull(store.findAlert(alert.messageId))
    }

    @Test
    fun rejectedAlertLeavesPendingQueue() = runBlocking {
        val alert = SafetyAlertRecord(
            messageId = "message",
            alertId = "alert",
            deviceId = "device",
            alarmType = "FALL",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 1,
            sampleReference = null,
            monotonicMillis = 1,
            occurredAtEpochMillis = 2,
            localActions = 7,
            sensorFaults = 0,
            simulated = true,
            sensorSnapshotJson = "{}",
            latitude = null,
            longitude = null,
            horizontalAccuracyMeters = null,
            locationFixType = "NO_FIX",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(store.recordAlert(alert))
        assertTrue(store.markRejected(alert.messageId, "invalid"))
        assertEquals(0, store.pendingAlertCount())
        assertEquals(DeliveryState.REJECTED, store.findAlert(alert.messageId)?.deliveryState)
    }
}
