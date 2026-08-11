package com.example.helmet.service.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAlarmPersistenceTest {
    @Test
    fun successAckOccursAfterSnapshotAndAlarmTransaction() = runBlocking {
        val order = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            sequence = 91,
            persist = {
                order += "sample"
                order += "alarm"
                true
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )

        assertTrue(result.persisted)
        assertEquals(true, result.value)
        assertEquals(listOf("sample", "alarm", "ack:91:0"), order)
    }

    @Test
    fun storageFailureReturnsErrorAckAndNoSuccess() = runBlocking {
        val order = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            sequence = 91,
            persist = {
                order += "sample"
                error("alarm store unavailable")
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )

        assertFalse(result.persisted)
        assertNull(result.value)
        assertEquals(listOf("sample", "ack:91:7"), order)
    }

    @Test
    fun simulatedAlarmPersistsWithoutProtocolAck() = runBlocking {
        var acknowledgeCalls = 0

        val result = persistAndAcknowledgeSafetyAlarm(
            sequence = null,
            persist = { "stored" },
            acknowledge = { _, _ -> acknowledgeCalls++ },
        )

        assertTrue(result.persisted)
        assertEquals("stored", result.value)
        assertEquals(0, acknowledgeCalls)
    }
}
