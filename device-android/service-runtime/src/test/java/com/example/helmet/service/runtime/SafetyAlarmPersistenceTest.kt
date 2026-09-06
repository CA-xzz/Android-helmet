package com.example.helmet.service.runtime

import com.example.helmet.data.local.DurableIdentityConflictException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAlarmPersistenceTest {
    private class AcknowledgementCapability(val diagnosticSequence: Int)

    @Test
    fun persistenceForwardsTheOriginalAcknowledgementCapability() = runBlocking {
        val capability = AcknowledgementCapability(diagnosticSequence = 91)
        var acknowledged: AcknowledgementCapability? = null

        val result = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = capability,
            persist = { true },
            acknowledge = { token, _ -> acknowledged = token },
        )

        assertTrue(result.persisted)
        assertSame(capability, acknowledged)
    }

    @Test
    fun successAckOccursAfterSnapshotAndAlarmTransaction() = runBlocking {
        val order = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = 91,
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
    fun storageFailureWithholdsAckSoSameReliableFrameCanRetry() = runBlocking {
        val order = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = 91,
            persist = {
                order += "sample"
                error("alarm store unavailable")
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )

        assertFalse(result.persisted)
        assertNull(result.value)
        assertEquals(listOf("sample"), order)

        val retried = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = 91,
            persist = {
                order += "sample-retry"
                true
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )
        assertTrue(retried.persisted)
        assertEquals(listOf("sample", "sample-retry", "ack:91:0"), order)
    }

    @Test
    fun simulatedAlarmPersistsWithoutProtocolAck() = runBlocking {
        var acknowledgeCalls = 0

        val result = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = null as Int?,
            persist = { "stored" },
            acknowledge = { _, _ -> acknowledgeCalls++ },
        )

        assertTrue(result.persisted)
        assertEquals("stored", result.value)
        assertEquals(0, acknowledgeCalls)
    }

    @Test
    fun reliableSensorSampleAcknowledgesOnlyAfterDurableWriteAndCanRetryFailure() = runBlocking {
        val order = mutableListOf<String>()

        val failed = persistAndAcknowledgeSafetySample(
            acknowledgement = 17,
            persist = {
                order += "failed-write"
                error("database busy")
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )
        val retried = persistAndAcknowledgeSafetySample(
            acknowledgement = 17,
            persist = {
                order += "stored"
                true
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )

        assertFalse(failed.persisted)
        assertTrue(retried.persisted)
        assertEquals(listOf("failed-write", "stored", "ack:17:0"), order)
    }

    @Test
    fun reusedBusinessIdentityWithDifferentContentGetsOnePermanentErrorAck() = runBlocking {
        val acknowledgements = mutableListOf<Pair<Int, Int>>()

        val result = persistAndAcknowledgeSafetySample(
            acknowledgement = 17,
            persist = { throw DurableIdentityConflictException("sample ID reused") },
            acknowledge = { sequence, code -> acknowledgements += sequence to code },
        )

        assertFalse(result.persisted)
        assertEquals(listOf(17 to 4), acknowledgements)
    }

    @Test
    fun recoverableClearStateCommitFailurePreventsRoomCommitAndProtocolAck() = runBlocking {
        val order = mutableListOf<String>()

        val result = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = 18,
            persist = {
                order += "desired-clear-state"
                check(false) { "SharedPreferences commit failed" }
                order += "room-alert"
            },
            acknowledge = { sequence, code -> order += "ack:$sequence:$code" },
        )

        assertFalse(result.persisted)
        assertEquals(listOf("desired-clear-state"), order)
    }
}
