package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.TextBroadcast
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationStoreInstrumentedTest {
    private lateinit var database: HelmetDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HelmetDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun callLifecycleIsPersistentOrderedAndIdempotent() = runBlocking {
        var time = 100L
        val store = CallStore(database) { time++ }
        val created = requireNotNull(store.createOutgoing("device-1", "event-1", simulated = true, callId = "call-1"))
        assertEquals(CallState.REQUESTED, created.state)
        assertEquals(1, created.stateSequence)
        assertFalse(store.createOutgoing("device-1", null, true, callId = "call-1") != null)
        assertEquals(CallState.RINGING, store.transition("call-1", CallState.RINGING).state)
        assertEquals(2L, store.find("call-1")?.stateSequence)
        assertEquals(2, store.transition("call-1", CallState.RINGING).stateSequence)
        assertEquals(CallState.ACCEPTED, store.transition("call-1", CallState.ACCEPTED).state)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.transition("call-1", CallState.REQUESTED) }
        }
        assertTrue(store.markAttempt("call-1", 200))
        assertTrue(store.markDelivered("call-1", 201))
        assertEquals(DeliveryState.DELIVERED, store.find("call-1")?.deliveryState)
    }

    @Test
    fun broadcastPersistsPlaybackAndReceiptStates() = runBlocking {
        val store = BroadcastStore(database)
        val message = TextBroadcast(
            broadcastId = "broadcast-1",
            deviceId = "device-1",
            serverSequence = 1,
            text = "请立即撤离",
            language = "zh-CN",
            priority = 10,
            expiresAtEpochMillis = 10_000,
            playbackState = BroadcastPlaybackState.RECEIVED,
            receivedAtEpochMillis = 100,
            playingAtEpochMillis = null,
            playedAtEpochMillis = null,
            lastError = null,
            receiptDeliveryState = DeliveryState.PENDING,
            receiptAttemptCount = 0,
        )
        assertTrue(store.receive(message))
        assertFalse(store.receive(message))
        assertTrue(store.updatePlayback("broadcast-1", BroadcastPlaybackState.PLAYING, 200))
        assertTrue(store.updatePlayback("broadcast-1", BroadcastPlaybackState.FAILED, 300, "TTS_FAILURE"))
        assertEquals(BroadcastPlaybackState.FAILED, store.find("broadcast-1")?.playbackState)
        assertTrue(store.markReceiptAttempt("broadcast-1", 400))
        assertTrue(store.markReceiptFailed("broadcast-1"))
        assertEquals("TTS_FAILURE", store.find("broadcast-1")?.lastError)
        assertTrue(store.markReceiptAttempt("broadcast-1", 450))
        assertTrue(store.markReceiptDelivered("broadcast-1", 500))
        assertEquals(DeliveryState.DELIVERED, store.find("broadcast-1")?.receiptDeliveryState)
    }

    @Test
    fun commandIsStoredBeforeApplicationAndAck() = runBlocking {
        val store = DeviceCommandStore(database)
        val command = DeviceCommand(
            commandId = "command-1",
            deviceId = "device-1",
            serverSequence = 1,
            type = DeviceCommandType.CALL_STATE,
            payloadJson = "{\"callId\":\"call-1\",\"state\":\"RINGING\"}",
            createdAtEpochMillis = 100,
            state = DeviceCommandState.RECEIVED,
            receivedAtEpochMillis = 101,
            appliedAtEpochMillis = null,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )
        assertTrue(store.receive(command))
        assertFalse(store.receive(command))
        assertEquals(1, store.maxSequence("device-1"))
        assertEquals(listOf("command-1"), store.pendingApplication().map { it.commandId })
        assertTrue(store.markApplied("command-1", 200))
        assertEquals(DeviceCommandState.APPLIED, store.find("command-1")?.state)
        assertTrue(store.markAckAttempt("command-1", 300))
        assertTrue(store.markAckDelivered("command-1", 400))
        assertEquals(DeliveryState.DELIVERED, store.find("command-1")?.ackDeliveryState)
    }
}
