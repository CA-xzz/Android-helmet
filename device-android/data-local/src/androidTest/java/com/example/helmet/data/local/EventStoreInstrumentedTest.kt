package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.EventSeverity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(store.record("SIMULATED_FALL", EventSeverity.HIGH, "{}"))
        assertEquals(1, database.eventDao().totalCount())
        assertEquals(1, store.totalCount())
    }
}
