package com.example.helmet.service.runtime

import com.example.helmet.core.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCallTransitionTest {
    @Test
    fun parsesRequiredServerSequenceAndEventTimeWithoutRewritingThem() {
        val transition = parseRemoteCallTransition(
            """{"callId":"call-1","state":"ACCEPTED","stateSequence":3,"occurredAtEpochMillis":1786000000123,"reason":"DISPATCHER_ACCEPTED"}""",
        )

        assertEquals("call-1", transition.callId)
        assertEquals(CallState.ACCEPTED, transition.state)
        assertEquals(3L, transition.stateSequence)
        assertEquals(1_786_000_000_123L, transition.occurredAtEpochMillis)
        assertEquals("DISPATCHER_ACCEPTED", transition.reason)
    }

    @Test
    fun rejectsMissingCoercedOrInitialStateFields() {
        listOf(
            """{"callId":"call-1","state":"RINGING","occurredAtEpochMillis":100}""",
            """{"callId":"call-1","state":"RINGING","stateSequence":"2","occurredAtEpochMillis":100}""",
            """{"callId":"call-1","state":"RINGING","stateSequence":1,"occurredAtEpochMillis":100}""",
            """{"callId":"call-1","state":"RINGING","stateSequence":2,"occurredAtEpochMillis":"100"}""",
            """{"callId":"call-1","state":"UNKNOWN","stateSequence":2,"occurredAtEpochMillis":100}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) { parseRemoteCallTransition(payload) }
        }
    }
}
