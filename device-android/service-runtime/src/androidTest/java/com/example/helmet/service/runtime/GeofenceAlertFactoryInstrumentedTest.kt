package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeofenceAlertFactoryInstrumentedTest {
    @Test
    fun exitProducesPendingGeofenceAlertOnAndroidRuntime() {
        val fix = LocationFix(
            fixId = "board-geofence-fix",
            deviceId = "board-geofence-device",
            occurredAtEpochMillis = 1_786_000_000_000,
            elapsedRealtimeNanos = 8_000_000,
            source = LocationSource.REPLAY,
            quality = FixQuality.STANDARD,
            latitude = 31.2304,
            longitude = 121.4737,
            horizontalAccuracyMeters = 2f,
            provider = "test",
            isMock = false,
        )
        val alert = GeofenceAlertFactory.create(
            deviceId = fix.deviceId,
            transition = GeofenceTransition(
                geofenceId = "board-yard",
                type = GeofenceTransitionType.EXIT,
                fixId = fix.fixId,
                occurredAtEpochMillis = fix.occurredAtEpochMillis,
                distanceMeters = 151.0,
            ),
            fix = fix,
            previousAlertActive = false,
        )!!

        assertEquals("GEOFENCE", alert.alarmType)
        assertTrue(alert.active)
        assertEquals("board-yard", JSONObject(alert.sensorSnapshotJson).getString("geofenceId"))
    }
}
