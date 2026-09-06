package com.example.helmet.service.runtime

import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.hardware.api.HardwareGateway
import com.example.helmet.hardware.api.HardwareStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareRuntimeMaintenanceTest {
    @Test
    fun generationCounterRejectsLongMaxInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, nextHardwareMaintenanceGeneration(Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) {
            nextHardwareMaintenanceGeneration(Long.MAX_VALUE)
        }
    }

    @Test
    fun pauseBlocksDelayedInitialStartAndResumeIsIdempotent() = runBlocking {
        val gateway = RecordingHardwareGateway()
        val registration = HardwareRuntimeMaintenance.register(gateway)
        try {
            val lease = HardwareRuntimeMaintenance.pause().lease

            assertFalse(HardwareRuntimeMaintenance.startRegistered(registration))
            assertEquals(0, gateway.startCalls)
            assertEquals(1, gateway.stopCalls)

            assertTrue(HardwareRuntimeMaintenance.resume(lease))
            assertTrue(HardwareRuntimeMaintenance.resume(lease))
            assertEquals(1, gateway.startCalls)
        } finally {
            HardwareRuntimeMaintenance.unregisterAndStop(registration)
        }
        assertEquals(2, gateway.stopCalls)
    }

    @Test
    fun failedResumeRetainsLeaseForRetry() = runBlocking {
        val gateway = RecordingHardwareGateway(startFailures = 1)
        val registration = HardwareRuntimeMaintenance.register(gateway)
        try {
            val lease = HardwareRuntimeMaintenance.pause().lease

            assertTrue(runCatching { HardwareRuntimeMaintenance.resume(lease) }.isFailure)
            assertTrue(HardwareRuntimeMaintenance.resume(lease))
            assertEquals(2, gateway.startCalls)
        } finally {
            HardwareRuntimeMaintenance.unregisterAndStop(registration)
        }
    }

    @Test
    fun failedPauseRollsBackToRunningGateway() = runBlocking {
        val gateway = RecordingHardwareGateway(stopFailures = 1)
        val registration = HardwareRuntimeMaintenance.register(gateway)
        try {
            val outcome = HardwareRuntimeMaintenance.pause()
            assertTrue(outcome.failure is IllegalStateException)
            assertEquals(1, gateway.startCalls)
            assertTrue(HardwareRuntimeMaintenance.resume(outcome.lease))
            assertEquals(1, gateway.startCalls)
        } finally {
            HardwareRuntimeMaintenance.unregisterAndStop(registration)
        }
    }

    @Test
    fun failedPauseAndFailedRollbackStillReturnRecoverableLease() = runBlocking {
        val gateway = RecordingHardwareGateway(startFailures = 1, stopFailures = 1)
        val registration = HardwareRuntimeMaintenance.register(gateway)
        try {
            val outcome = HardwareRuntimeMaintenance.pause()

            assertTrue(outcome.failure is IllegalStateException)
            assertEquals(1, outcome.failure?.suppressed?.size)
            assertEquals(1, gateway.startCalls)
            assertTrue(HardwareRuntimeMaintenance.resume(outcome.lease))
            assertEquals(2, gateway.startCalls)
        } finally {
            HardwareRuntimeMaintenance.unregisterAndStop(registration)
        }
    }

    @Test
    fun obsoleteServiceGenerationCannotStartOrResume() = runBlocking {
        val oldGateway = RecordingHardwareGateway()
        val oldRegistration = HardwareRuntimeMaintenance.register(oldGateway)
        val oldLease = HardwareRuntimeMaintenance.pause().lease
        assertTrue(HardwareRuntimeMaintenance.unregisterAndStop(oldRegistration))

        val currentGateway = RecordingHardwareGateway()
        val currentRegistration = HardwareRuntimeMaintenance.register(currentGateway)
        try {
            assertFalse(HardwareRuntimeMaintenance.startRegistered(oldRegistration))
            assertFalse(HardwareRuntimeMaintenance.resume(oldLease))
            assertEquals(0, oldGateway.startCalls)
            assertEquals(0, currentGateway.startCalls)

            assertTrue(HardwareRuntimeMaintenance.startRegistered(currentRegistration))
            assertEquals(1, currentGateway.startCalls)
        } finally {
            HardwareRuntimeMaintenance.unregisterAndStop(currentRegistration)
        }
    }
}

private class RecordingHardwareGateway(
    private var startFailures: Int = 0,
    private var stopFailures: Int = 0,
) : HardwareGateway {
    private val mutableStatus = MutableStateFlow(HardwareStatus())

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: Flow<HardwareEvent> = emptyFlow()
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override suspend fun start() {
        startCalls += 1
        if (startFailures > 0) {
            startFailures -= 1
            throw IllegalStateException("injected start failure")
        }
    }

    override suspend fun stop() {
        stopCalls += 1
        if (stopFailures > 0) {
            stopFailures -= 1
            throw IllegalStateException("injected stop failure")
        }
    }

    override suspend fun send(command: HardwareCommand) = Unit
}
