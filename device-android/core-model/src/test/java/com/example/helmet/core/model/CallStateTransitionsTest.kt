package com.example.helmet.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTransitionsTest {
    @Test
    fun acceptsNormalCallLifecycleAndIdempotentReplay() {
        assertTrue(CallStateTransitions.canTransition(CallState.REQUESTED, CallState.RINGING))
        assertTrue(CallStateTransitions.canTransition(CallState.RINGING, CallState.ACCEPTED))
        assertTrue(CallStateTransitions.canTransition(CallState.ACCEPTED, CallState.CONNECTING))
        assertTrue(CallStateTransitions.canTransition(CallState.CONNECTING, CallState.CONNECTED))
        assertTrue(CallStateTransitions.canTransition(CallState.CONNECTED, CallState.ENDED))
        assertTrue(CallStateTransitions.canTransition(CallState.CONNECTED, CallState.CONNECTED))
    }

    @Test
    fun rejectsTerminalAndBackwardTransitions() {
        assertFalse(CallStateTransitions.canTransition(CallState.REJECTED, CallState.RINGING))
        assertFalse(CallStateTransitions.canTransition(CallState.ENDED, CallState.CONNECTED))
        assertFalse(CallStateTransitions.canTransition(CallState.CONNECTED, CallState.ACCEPTED))
    }
}
