package com.example.helmet.feature.location

import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEngineTest {
    @Test
    fun hysteresisAndConfirmationSuppressBoundaryNoise() {
        val engine = GeofenceEngine(
            listOf(
                CircleGeofence(
                    geofenceId = "yard",
                    centerLatitude = 30.0,
                    centerLongitude = 114.0,
                    radiusMeters = 100.0,
                    hysteresisMeters = 10.0,
                    confirmationSamples = 2,
                ),
            ),
        )

        assertTrue(engine.evaluate(fix("outside", 30.0012)).isEmpty())
        assertTrue(engine.evaluate(fix("inside-1", 30.0005)).isEmpty())
        assertEquals(GeofenceTransitionType.ENTER, engine.evaluate(fix("inside-2", 30.0005)).single().type)
        assertTrue(engine.evaluate(fix("boundary", 30.00095)).isEmpty())
        assertTrue(engine.evaluate(fix("exit-1", 30.0012)).isEmpty())
        assertEquals(GeofenceTransitionType.EXIT, engine.evaluate(fix("exit-2", 30.0012)).single().type)
    }

    @Test
    fun mockAndNoFixNeverTrigger() {
        val engine = GeofenceEngine(listOf(CircleGeofence("yard", 30.0, 114.0, 100.0)))
        assertTrue(engine.evaluate(fix("mock", 30.0, mock = true)).isEmpty())
        assertTrue(
            engine.evaluate(
                LocationFix(
                    fixId = "no-fix",
                    deviceId = "device",
                    occurredAtEpochMillis = 1,
                    elapsedRealtimeNanos = null,
                    source = LocationSource.REPLAY,
                    quality = FixQuality.NO_FIX,
                    latitude = null,
                    longitude = null,
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun initialConfirmedOutsideFixRaisesExitViolation() {
        val engine = GeofenceEngine(
            listOf(CircleGeofence("yard", 30.0, 114.0, 100.0, confirmationSamples = 2)),
        )
        assertTrue(engine.evaluate(fix("outside-1", 30.002)).isEmpty())
        assertEquals(GeofenceTransitionType.EXIT, engine.evaluate(fix("outside-2", 30.002)).single().type)
    }

    private fun fix(id: String, latitude: Double, mock: Boolean = false) = LocationFix(
        fixId = id,
        deviceId = "device",
        occurredAtEpochMillis = 1,
        elapsedRealtimeNanos = null,
        source = LocationSource.REPLAY,
        quality = FixQuality.STANDARD,
        latitude = latitude,
        longitude = 114.0,
        horizontalAccuracyMeters = 2f,
        isMock = mock,
    )
}
