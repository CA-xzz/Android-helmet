package com.example.helmet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelmetStateMachineTest {
    @Test
    fun bootSequenceAcceptsOfflineReady() {
        val machine = HelmetStateMachine()

        assertTrue(machine.transitionTo(HelmetOperationalState.SELF_TEST))
        assertTrue(machine.transitionTo(HelmetOperationalState.OFFLINE_READY))
        assertEquals(HelmetOperationalState.OFFLINE_READY, machine.current())
    }

    @Test
    fun invalidBootToCallTransitionIsRejected() {
        val machine = HelmetStateMachine()

        assertFalse(machine.transitionTo(HelmetOperationalState.IN_CALL))
        assertEquals(HelmetOperationalState.BOOTING, machine.current())
    }

    @Test
    fun safetyAlarmCanPreemptRecording() {
        val machine = HelmetStateMachine(HelmetOperationalState.IDLE)

        assertTrue(machine.transitionTo(HelmetOperationalState.RECORDING))
        assertTrue(machine.transitionTo(HelmetOperationalState.SOS))
    }
}
