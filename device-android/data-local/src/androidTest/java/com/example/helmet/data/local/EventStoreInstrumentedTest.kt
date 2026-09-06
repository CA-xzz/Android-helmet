package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.EventSeverity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventStoreInstrumentedTest {
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
    fun duplicateMessageIdIsIdempotent() = runBlocking {
        val store = EventStore(database, wallClock = { 100L }, idFactory = { "fixed-id" })

        assertTrue(store.record("SIMULATED_FALL", EventSeverity.HIGH, "{}"))
        assertFalse(
            store.record(
                "SIMULATED_FALL",
                EventSeverity.HIGH,
                "{}",
                occurredAtEpochMillis = 200L,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.record("SIMULATED_FALL", EventSeverity.HIGH, "{\"different\":true}")
            }
        }
        assertEquals(1, database.eventDao().totalCount())
        assertEquals(1, store.totalCount())
    }

    @Test
    fun retentionKeepsOnlyNewestInsertedEvents() = runBlocking {
        var nextId = 0
        val store = EventStore(
            database = database,
            idFactory = { "event-${++nextId}" },
            maxRetainedEvents = 2,
        )

        assertTrue(store.record("FIRST", EventSeverity.INFO, "{}", occurredAtEpochMillis = 300L))
        assertTrue(store.record("SECOND", EventSeverity.INFO, "{}", occurredAtEpochMillis = 100L))
        assertTrue(store.record("THIRD", EventSeverity.INFO, "{}", occurredAtEpochMillis = 200L))

        assertEquals(2, store.totalCount())
        assertEquals(
            setOf("event-2", "event-3"),
            database.eventDao().observeRecent(10).first().map { it.messageId }.toSet(),
        )
    }

    @Test
    fun concurrentRecordsAreTransactionallyBoundedWithoutLosingInsertResults() = runBlocking {
        val store = EventStore(database = database, maxRetainedEvents = 25)

        val inserted = (1..100).map { ordinal ->
            async(Dispatchers.IO) {
                store.record(
                    eventType = "CONCURRENT",
                    severity = EventSeverity.INFO,
                    payloadJson = "{\"ordinal\":$ordinal}",
                    messageId = "event-$ordinal",
                    occurredAtEpochMillis = ordinal.toLong(),
                )
            }
        }.awaitAll()

        assertTrue(inserted.all { it })
        assertEquals(25, store.totalCount())
        assertEquals(25, database.eventDao().observeRecent(100).first().size)
    }
}
