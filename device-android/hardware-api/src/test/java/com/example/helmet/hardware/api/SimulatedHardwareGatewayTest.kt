package com.example.helmet.hardware.api

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHardwareGatewayTest {
    @Test
    fun injectedFallIsExplicitlyMarkedSimulated() = runTest {
        val gateway = SimulatedHardwareGateway(this, monotonicClock = { 42L })
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.first { it is HardwareEvent.Alarm }
        }

        gateway.inject(SimulatedInput.FALL)

        val alarm = received.await() as HardwareEvent.Alarm
        assertEquals("FALL", alarm.alarmType)
        assertEquals(42L, alarm.sampleReference)
        assertTrue(alarm.simulated)
    }

    @Test
    fun sampleReferenceStaysWithinUnsigned32BitRange() = runTest {
        val gateway = SimulatedHardwareGateway(this, monotonicClock = { 0x1_0000_002AL })
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<HardwareEvent.SensorSample>().first()
        }

        gateway.inject(SimulatedInput.FALL)

        assertEquals(42L, received.await().sampleReference)
    }
}
