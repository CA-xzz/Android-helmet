package com.example.helmet.service.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.SafetyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareKeyActionRecoveryInstrumentedTest {
    @Test
    fun sosEventIdSeparatesEqualMonotonicValuesAndReplayIsIdempotent() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            HelmetDatabase::class.java,
        ).build()
        try {
            val store = SafetyStore(database)
            val first = sosAlert(eventId = 101, monotonicMillis = 7)
            val second = sosAlert(eventId = 102, monotonicMillis = 7)

            assertTrue(store.recordAlert(first))
            assertTrue(store.recordAlert(second))
            assertFalse(store.recordAlert(first))
            assertEquals(2, store.pendingAlertCount())
            assertEquals(first, store.findAlert(first.messageId))
            assertEquals(second, store.findAlert(second.messageId))
        } finally {
            database.close()
        }
    }

    private fun sosAlert(eventId: Long, monotonicMillis: Long): SafetyAlertRecord {
        val alarmId = sosAlarmBusinessId(eventId, "ignored")
        val alertId = "device:$alarmId"
        return SafetyAlertRecord(
            messageId = "$alertId:ACTIVE:$monotonicMillis",
            alertId = alertId,
            deviceId = "device",
            alarmType = "SOS",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = null,
            sampleReference = null,
            monotonicMillis = monotonicMillis,
            occurredAtEpochMillis = 1_000,
            localActions = 0,
            sensorFaults = 0,
            simulated = false,
            sensorSnapshotJson = "{}",
            latitude = null,
            longitude = null,
            horizontalAccuracyMeters = null,
            locationFixType = "NO_FIX",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
    }
}
