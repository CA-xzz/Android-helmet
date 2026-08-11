package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCommandStoreInstrumentedTest {
    @Test
    fun pendingAcknowledgementsIncludeAppliedCommandsButExcludeUnappliedCommands() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val deviceId = "mqtt-test-$nonce"
        val applied = command("applied-$nonce", deviceId, 1)
        val received = command("received-$nonce", deviceId, 2)
        assertTrue(store.receive(applied))
        assertTrue(store.receive(received))
        assertTrue(store.markApplied(applied.commandId, System.currentTimeMillis()))

        val pending = store.pendingAcks(100).filter { it.deviceId == deviceId }
        assertEquals(listOf(applied.commandId), pending.map(DeviceCommand::commandId))
        assertFalse(pending.any { it.commandId == received.commandId })

        assertTrue(store.markAckAttempt(applied.commandId, System.currentTimeMillis()))
        assertTrue(store.markAckDelivered(applied.commandId, System.currentTimeMillis()))
        assertTrue(store.pendingAcks(100).none { it.commandId == applied.commandId })
    }

    private fun command(id: String, deviceId: String, sequence: Long) = DeviceCommand(
        commandId = id,
        deviceId = deviceId,
        serverSequence = sequence,
        type = DeviceCommandType.CALL_STATE,
        payloadJson = "{}",
        createdAtEpochMillis = System.currentTimeMillis(),
        state = DeviceCommandState.RECEIVED,
        receivedAtEpochMillis = System.currentTimeMillis(),
        appliedAtEpochMillis = null,
        lastError = null,
        ackDeliveryState = DeliveryState.PENDING,
        ackAttemptCount = 0,
    )
}
