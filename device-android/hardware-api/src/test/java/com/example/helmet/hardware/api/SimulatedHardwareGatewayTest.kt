package com.example.helmet.hardware.api

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHardwareGatewayTest {
    @Test
    fun injectedFallUsesSimulatedRawImuSamplesWithoutDirectAlarm() = runTest {
        val gateway = SimulatedHardwareGateway(this, monotonicClock = { 42L })
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<HardwareEvent.SensorSample>().take(4).toList()
        }

        gateway.inject(SimulatedInput.FALL)

        val samples = received.await()
        assertEquals(listOf(42L, 43L, 44L, 45L), samples.map { it.sampleReference })
        assertEquals(listOf(42L, 142L, 282L, 342L), samples.map { it.monotonicMillis })
        assertTrue(samples.all(HardwareEvent.SensorSample::simulated))
        assertEquals(listOf(1_000, 100, 100, 0), samples.map { it.accelerationZMilliG })
        assertEquals(2_400, samples.last().accelerationXMilliG)
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

    @Test
    fun injectedNearElectricUsesStableBaselineAndRiskSamplesWithoutDirectAlarm() = runTest {
        val gateway = SimulatedHardwareGateway(this, monotonicClock = { 42L })
        val samples = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<HardwareEvent.SensorSample>().take(13).toList()
        }
        val directAlarm = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1) {
                gateway.events.filterIsInstance<HardwareEvent.Alarm>().first()
            }
        }

        gateway.inject(SimulatedInput.NEAR_ELECTRIC)

        val received = samples.await()
        assertEquals((42L..54L).toList(), received.map { it.sampleReference })
        assertEquals((0L..12L).map { 42L + it * 100L }, received.map { it.monotonicMillis })
        assertTrue(received.all(HardwareEvent.SensorSample::simulated))
        assertEquals(List(10) { 100 } + List(3) { 920 }, received.map { it.electricFieldMilliVolts })
        assertNull(directAlarm.await())
    }

    @Test
    fun injectedHeightUsesPressureBaselineAndRiskSamplesWithoutDirectAlarm() = runTest {
        val gateway = SimulatedHardwareGateway(this, monotonicClock = { 42L })
        val samples = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<HardwareEvent.SensorSample>().take(13).toList()
        }
        val directAlarm = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1) {
                gateway.events.filterIsInstance<HardwareEvent.Alarm>().first()
            }
        }

        gateway.inject(SimulatedInput.HEIGHT_LIMIT)

        val received = samples.await()
        assertEquals((42L..54L).toList(), received.map { it.sampleReference })
        assertTrue(received.all(HardwareEvent.SensorSample::simulated))
        assertEquals(List(10) { 101_325L } + List(3) { 101_285L }, received.map { it.pressurePascals })
        assertTrue(received.all { it.altitudeMillimetres == null })
        assertNull(directAlarm.await())
    }

    @Test
    fun startedGatewayAllocatesAfterDurableHighWater() = runTest {
        val gateway = SimulatedHardwareGateway(
            scope = this,
            monotonicClock = { 42L },
            durableSampleReferenceHighWater = { 91_810_927L },
        )
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<HardwareEvent.SensorSample>().first()
        }

        gateway.start()
        gateway.inject(SimulatedInput.FALL)

        assertEquals(91_810_928L, received.await().sampleReference)
        gateway.stop()
    }

    @Test
    fun fallSimulationFailsBeforePartialEmissionWhenReferenceSpaceIsExhausted() = runTest {
        val gateway = SimulatedHardwareGateway(
            scope = this,
            monotonicClock = { 0xFFFF_FFFEL },
        )

        val failure = runCatching { gateway.inject(SimulatedInput.FALL) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun nearElectricSimulationFailsBeforePartialEmissionWhenReferenceSpaceIsExhausted() = runTest {
        val gateway = SimulatedHardwareGateway(
            scope = this,
            monotonicClock = { 0xFFFF_FFF5L },
        )

        val failure = runCatching { gateway.inject(SimulatedInput.NEAR_ELECTRIC) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun heightSimulationFailsBeforePartialEmissionWhenReferenceSpaceIsExhausted() = runTest {
        val gateway = SimulatedHardwareGateway(
            scope = this,
            monotonicClock = { 0xFFFF_FFF5L },
        )

        val failure = runCatching { gateway.inject(SimulatedInput.HEIGHT_LIMIT) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
