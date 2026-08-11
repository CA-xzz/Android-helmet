package com.example.helmet.service.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.SafetyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableAlarmAckInstrumentedTest {
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
    fun successAckObservesBothEmbeddedSampleAndAlertInRoom() = runBlocking {
        val sample = SafetySensorTelemetry(
            sampleId = "helmet-h618:77",
            deviceId = "helmet-h618",
            sampleReference = 77,
            monotonicMillis = 900,
            validFlags = 0x0007,
            accelerationXMilliG = 4_000,
            accelerationYMilliG = 0,
            accelerationZMilliG = 0,
            gyroXMilliDegreesPerSecond = 1,
            gyroYMilliDegreesPerSecond = 2,
            gyroZMilliDegreesPerSecond = 3,
            electricFieldMilliVolts = 250,
            pressurePascals = 101_325,
            temperatureCentiCelsius = null,
            altitudeMillimetres = 1_000,
            simulated = false,
            recordedAtEpochMillis = 1_000,
        )
        val alert = SafetyAlertRecord(
            messageId = "helmet-h618:alarm:9:ACTIVE:77",
            alertId = "helmet-h618:9",
            deviceId = "helmet-h618",
            alarmType = "IMPACT",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 3,
            sampleReference = 77,
            monotonicMillis = 900,
            occurredAtEpochMillis = 1_000,
            localActions = 7,
            sensorFaults = 0,
            simulated = false,
            sensorSnapshotJson = "{\"sampleReference\":77,\"accelerationXMilliG\":4000}",
            latitude = null,
            longitude = null,
            horizontalAccuracyMeters = null,
            locationFixType = "NO_FIX",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        val acknowledgements = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            sequence = 91,
            persist = {
                assertTrue(store.recordSample(sample))
                assertTrue(store.recordAlert(alert))
                "stored"
            },
            acknowledge = { sequence, code ->
                assertNotNull(store.findSample("helmet-h618", 77))
                assertNotNull(store.findAlert(alert.messageId))
                acknowledgements += "$sequence:$code"
            },
        )

        assertTrue(result.persisted)
        assertEquals("stored", result.value)
        assertEquals(listOf("91:0"), acknowledgements)
        assertEquals(1, store.pendingAlertCount())
    }
}
