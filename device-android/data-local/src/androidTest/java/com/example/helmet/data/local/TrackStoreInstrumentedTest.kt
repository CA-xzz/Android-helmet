package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackStoreInstrumentedTest {
    private lateinit var database: HelmetDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HelmetDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun assignsStableSequenceAndPersistsQualityAndDelivery() = runBlocking {
        val store = TrackStore(database) { 900 }
        val first = store.record(fix("fix-1", 100), "point-1")!!
        val second = store.record(fix("fix-2", 200), "point-2")!!

        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(listOf(1L, 2L), store.pending().map { it.sequence })
        assertEquals(FixQuality.RTK_FIXED, store.find("point-1")?.fix?.quality)
        assertEquals(12, store.find("point-1")?.fix?.gnss?.satellitesUsed)
        assertNull(store.record(fix("fix-duplicate", 300), "point-1"))
        assertTrue(store.markAttempt("point-1", 1_000))
        assertEquals(DeliveryState.IN_FLIGHT, store.find("point-1")?.deliveryState)
        assertTrue(store.markDelivered("point-1", 1_100))
        assertEquals(DeliveryState.DELIVERED, store.find("point-1")?.deliveryState)
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun rejectsNoFixAndMockLocations() {
        val store = TrackStore(database)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.record(noFix(), "no-fix") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.record(fix("mock", 100).copy(isMock = true), "mock") }
        }
        runBlocking { assertFalse(store.find("mock") != null) }
    }

    private fun fix(id: String, time: Long) = LocationFix(
        fixId = id,
        deviceId = "device-1",
        occurredAtEpochMillis = time,
        elapsedRealtimeNanos = time,
        source = LocationSource.EXTERNAL_NMEA,
        quality = FixQuality.RTK_FIXED,
        latitude = 30.5,
        longitude = 114.3,
        altitudeMeters = 25.0,
        horizontalAccuracyMeters = 0.2f,
        gnss = GnssQuality(
            satellitesUsed = 12,
            satellitesVisible = 18,
            hdop = 0.7,
            correctionAgeSeconds = 1.0,
            correctionStationId = "1001",
        ),
        provider = "external-rtk",
    )

    private fun noFix() = LocationFix(
        fixId = "no-fix",
        deviceId = "device-1",
        occurredAtEpochMillis = 100,
        elapsedRealtimeNanos = null,
        source = LocationSource.ANDROID_FUSED,
        quality = FixQuality.NO_FIX,
        latitude = null,
        longitude = null,
    )
}
