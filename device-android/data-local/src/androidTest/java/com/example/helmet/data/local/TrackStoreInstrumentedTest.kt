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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        val store = TrackStore(database, wallClock = { 900 })
        val first = store.record(fix("fix-1", 100), "point-1")!!
        val second = store.record(fix("fix-2", 200), "point-2")!!

        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(listOf(1L, 2L), store.pending().map { it.sequence })
        assertEquals(FixQuality.RTK_FIXED, store.find("point-1")?.fix?.quality)
        assertEquals(12, store.find("point-1")?.fix?.gnss?.satellitesUsed)
        assertNull(store.record(fix("fix-1", 100), "point-1"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.record(fix("fix-conflict", 300), "point-1") }
        }
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

    @Test
    fun terminalRetentionPreservesPendingRowsAndSequenceHighWater() = runBlocking {
        val store = TrackStore(
            database = database,
            maxRetainedTerminalPointsPerDevice = 2,
        )
        repeat(6) { index ->
            val ordinal = index + 1
            store.record(fix("fix-$ordinal", ordinal.toLong()), "point-$ordinal")
        }

        assertTrue(store.markAttempt("point-5", 100L))
        assertTrue(store.markAttempt("point-6", 100L))
        assertTrue(store.markFailed("point-6", "retryable"))
        assertTrue(store.markDelivered("point-1", 101L))
        assertTrue(store.markRejected("point-2", "permanent"))
        assertTrue(store.markDelivered("point-3", 103L))

        assertNull(store.find("point-1"))
        assertEquals(DeliveryState.REJECTED, store.find("point-2")?.deliveryState)
        assertEquals(DeliveryState.DELIVERED, store.find("point-3")?.deliveryState)
        assertEquals(DeliveryState.PENDING, store.find("point-4")?.deliveryState)
        assertEquals(DeliveryState.IN_FLIGHT, store.find("point-5")?.deliveryState)
        assertEquals(DeliveryState.FAILED, store.find("point-6")?.deliveryState)
        assertEquals(7L, store.record(fix("fix-7", 7L), "point-7")?.sequence)
    }

    @Test
    fun pendingRowsDoNotConsumeTerminalRetentionAllowance() = runBlocking {
        val store = TrackStore(database, maxRetainedTerminalPointsPerDevice = 2)
        repeat(8) { index ->
            val ordinal = index + 1
            store.record(fix("fix-$ordinal", ordinal.toLong()), "point-$ordinal")
        }

        assertTrue(store.markDelivered("point-1", 101L))
        assertTrue(store.markRejected("point-2", "permanent"))
        assertTrue(store.markDelivered("point-3", 103L))

        assertNull(store.find("point-1"))
        assertEquals(DeliveryState.REJECTED, store.find("point-2")?.deliveryState)
        assertEquals(DeliveryState.DELIVERED, store.find("point-3")?.deliveryState)
        (4..8).forEach { ordinal ->
            assertEquals(DeliveryState.PENDING, store.find("point-$ordinal")?.deliveryState)
        }
        assertEquals(9L, store.record(fix("fix-9", 9L), "point-9")?.sequence)
    }

    @Test
    fun terminalRetentionIsIndependentPerDevice() = runBlocking {
        val store = TrackStore(database, maxRetainedTerminalPointsPerDevice = 1)
        val deviceTwoFix = fix("device-2-fix-1", 1L).copy(deviceId = "device-2")
        val deviceTwoFix2 = fix("device-2-fix-2", 2L).copy(deviceId = "device-2")
        store.record(fix("device-1-fix-1", 1L), "device-1-point-1")
        store.record(fix("device-1-fix-2", 2L), "device-1-point-2")
        store.record(deviceTwoFix, "device-2-point-1")
        store.record(deviceTwoFix2, "device-2-point-2")

        assertTrue(store.markDelivered("device-1-point-1", 11L))
        assertTrue(store.markDelivered("device-1-point-2", 12L))
        assertTrue(store.markDelivered("device-2-point-1", 13L))
        assertTrue(store.markDelivered("device-2-point-2", 14L))

        assertNull(store.find("device-1-point-1"))
        assertEquals(DeliveryState.DELIVERED, store.find("device-1-point-2")?.deliveryState)
        assertNull(store.find("device-2-point-1"))
        assertEquals(DeliveryState.DELIVERED, store.find("device-2-point-2")?.deliveryState)
        assertEquals(3L, store.record(fix("device-1-fix-3", 3L), "device-1-point-3")?.sequence)
        assertEquals(
            3L,
            store.record(fix("device-2-fix-3", 3L).copy(deviceId = "device-2"), "device-2-point-3")?.sequence,
        )
    }

    @Test
    fun concurrentRecordsReceiveUniqueContiguousSequences() = runBlocking {
        val store = TrackStore(database)
        val points = (1..40).map { ordinal ->
            async(Dispatchers.IO) {
                requireNotNull(
                    store.record(
                        fix("concurrent-fix-$ordinal", ordinal.toLong()),
                        "concurrent-point-$ordinal",
                    ),
                )
            }
        }.awaitAll()

        assertEquals((1L..40L).toList(), points.map { it.sequence }.sorted())
    }

    @Test
    fun lateDeliveryAcknowledgementCannotOverwritePermanentRejection() = runBlocking {
        val store = TrackStore(database)
        store.record(fix("rejected-fix", 1L), "rejected-point")

        assertTrue(store.markRejected("rejected-point", "permanent"))
        assertFalse(store.markDelivered("rejected-point", 2L))
        assertEquals(DeliveryState.REJECTED, store.find("rejected-point")?.deliveryState)
    }

    @Test
    fun maximumSequenceIsRetainedAndNeverWraps() = runBlocking {
        val store = TrackStore(database, maxRetainedTerminalPointsPerDevice = 1)
        store.record(fix("seed-fix", 1L), "seed-point")
        val seed = requireNotNull(database.trackPointDao().find("seed-point"))
        assertTrue(
            database.trackPointDao().insert(
                seed.copy(
                    messageId = "maximum-point",
                    sequence = Long.MAX_VALUE,
                    fixId = "maximum-fix",
                    occurredAtEpochMillis = 2L,
                ),
            ) != -1L,
        )
        assertTrue(store.markDelivered("maximum-point", 3L))

        assertEquals(Long.MAX_VALUE, database.trackPointDao().maxSequence("device-1"))
        assertEquals(DeliveryState.DELIVERED, store.find("maximum-point")?.deliveryState)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.record(fix("overflow-fix", 4L), "overflow-point") }
        }
        assertNull(store.find("overflow-point"))
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
