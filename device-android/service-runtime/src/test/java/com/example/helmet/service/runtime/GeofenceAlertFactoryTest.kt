package com.example.helmet.service.runtime

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceAlertFactoryTest {
    @Test
    fun exitCreatesStableActiveAlertWithTrustedLocation() {
        val transition = transition(GeofenceTransitionType.EXIT, "fix-exit", 175.5)
        val first = GeofenceAlertFactory.create("device-1", transition, fix("fix-exit"), false)!!
        val repeated = GeofenceAlertFactory.create("device-1", transition, fix("fix-exit"), false)!!

        assertEquals(first.messageId, repeated.messageId)
        assertEquals("GEOFENCE", first.alarmType)
        assertEquals(EventSeverity.HIGH, first.severity)
        assertTrue(first.active)
        assertFalse(first.simulated)
        assertEquals(31.2304, first.latitude!!, 0.0)
        assertEquals("yard", JSONObject(first.sensorSnapshotJson).getString("geofenceId"))
    }

    @Test
    fun enterOnlyClearsAnExistingActiveAlert() {
        val transition = transition(GeofenceTransitionType.ENTER, "fix-enter", 95.0)

        assertNull(GeofenceAlertFactory.create("device-1", transition, fix("fix-enter"), false))
        val cleared = GeofenceAlertFactory.create("device-1", transition, fix("fix-enter"), true)!!
        assertFalse(cleared.active)
        assertEquals(EventSeverity.INFO, cleared.severity)
        assertEquals("ENTER", JSONObject(cleared.sensorSnapshotJson).getString("transition"))
    }

    private fun transition(type: GeofenceTransitionType, fixId: String, distance: Double) =
        GeofenceTransition(
            geofenceId = "yard",
            type = type,
            fixId = fixId,
            occurredAtEpochMillis = 1_786_000_000_000,
            distanceMeters = distance,
        )

    private fun fix(fixId: String) = LocationFix(
        fixId = fixId,
        deviceId = "device-1",
        occurredAtEpochMillis = 1_786_000_000_000,
        elapsedRealtimeNanos = 12_345_000_000,
        source = LocationSource.ANDROID_GNSS,
        quality = FixQuality.STANDARD,
        latitude = 31.2304,
        longitude = 121.4737,
        horizontalAccuracyMeters = 2f,
        provider = "gps",
        isMock = false,
    )
}
