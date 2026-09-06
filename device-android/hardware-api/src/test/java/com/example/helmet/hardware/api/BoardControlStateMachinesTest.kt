package com.example.helmet.hardware.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardControlStateMachinesTest {
    @Test
    fun oneSecondPulseReturnsToInactive() = runTest {
        val levels = mutableListOf<Boolean>()
        val delays = mutableListOf<Long>()
        val executor = SafePulseExecutor(
            driver = LogicalLineDriver(levels::add),
            wait = delays::add,
        )

        executor.pulse(SafePulseSpec("modem_pwrkey", 1_000))

        assertEquals(listOf(false, true, false), levels)
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun cancelledPulseStillReturnsToInactive() = runTest {
        val levels = mutableListOf<Boolean>()
        val executor = SafePulseExecutor(
            driver = LogicalLineDriver(levels::add),
            wait = { throw CancellationException("cancelled") },
        )

        runCatching { executor.pulse(SafePulseSpec("modem_pwrkey", 1_000)) }

        assertEquals(listOf(false, true, false), levels)
    }

    @Test
    fun failedPowerSequenceRollsBackInReverseOrder() = runTest {
        val transitions = mutableListOf<Pair<String, Boolean>>()
        val driver = PowerLineDriver { resourceId, active ->
            transitions += resourceId to active
            if (resourceId == "audio" && active) error("driver failure")
        }
        val executor = PowerSequenceExecutor(driver, wait = {})

        runCatching {
            executor.execute(
                listOf(
                    PowerSequenceStep("rail", active = true, rollbackActive = false),
                    PowerSequenceStep("camera", active = true, rollbackActive = false),
                    PowerSequenceStep("audio", active = true, rollbackActive = false),
                ),
            )
        }

        assertEquals(
            listOf(
                "rail" to true,
                "camera" to true,
                "audio" to true,
                "camera" to false,
                "rail" to false,
            ),
            transitions,
        )
    }

    @Test
    fun buttonDebouncesAndEmitsOneShortPress() {
        val interpreter = PhysicalButtonInterpreter(debounceMillis = 40, longPressMillis = 2_000)

        assertTrue(interpreter.onRawLevel(true, 10).isEmpty())
        assertTrue(interpreter.onRawLevel(false, 20).isEmpty())
        assertTrue(interpreter.onRawLevel(true, 30).isEmpty())
        assertEquals(listOf(ButtonSignalType.PRESSED), interpreter.advanceTo(70).map(ButtonSignal::type))
        assertTrue(interpreter.onRawLevel(false, 300).isEmpty())
        val released = interpreter.advanceTo(340)

        assertEquals(
            listOf(ButtonSignalType.RELEASED, ButtonSignalType.SHORT_PRESS),
            released.map(ButtonSignal::type),
        )
        assertEquals(270L, released.last().heldDurationMillis)
        assertTrue(interpreter.advanceTo(500).isEmpty())
    }

    @Test
    fun buttonLongPressSurvivesSnapshotWithoutDuplicateBusinessEvent() {
        val first = PhysicalButtonInterpreter(debounceMillis = 40, longPressMillis = 2_000)
        first.onRawLevel(true, 100)
        first.advanceTo(140)
        val restored = PhysicalButtonInterpreter(
            debounceMillis = 40,
            longPressMillis = 2_000,
            snapshot = first.snapshot(),
        )

        assertEquals(listOf(ButtonSignalType.LONG_PRESS), restored.advanceTo(2_140).map(ButtonSignal::type))
        assertTrue(restored.advanceTo(3_000).isEmpty())
        restored.onRawLevel(false, 3_100)
        assertEquals(listOf(ButtonSignalType.RELEASED), restored.advanceTo(3_140).map(ButtonSignal::type))
    }

    @Test
    fun disconnectCancelsHeldButtonAndReconnectDoesNotCreateShortPress() {
        val interpreter = PhysicalButtonInterpreter(debounceMillis = 40, longPressMillis = 2_000)
        interpreter.onRawLevel(true, 100)
        interpreter.advanceTo(140)

        interpreter.disconnect(500)
        interpreter.reconnect(initialPressed = false, monotonicMillis = 1_000)

        assertTrue(interpreter.advanceTo(2_000).isEmpty())
    }

    @Test
    fun ledPriorityAndRestoreAreDeterministic() {
        val machine = LocalLedStateMachine()
        val state = machine.update(
            LocalLedInputs(
                serviceRunning = true,
                networkValidated = false,
                recording = true,
                callActive = false,
                hardwareFault = true,
                alarmActive = true,
            ),
        )

        assertEquals(LocalLedState.ALARM, state)
        val restored = LocalLedStateMachine(machine.snapshot())
        assertEquals(LocalLedState.ALARM, restored.snapshot().desiredState)
        assertEquals(
            LocalLedState.HARDWARE_FAULT,
            restored.update(
                LocalLedInputs(true, false, true, false, hardwareFault = true, alarmActive = false),
            ),
        )
    }

    @Test
    fun mmaAdapterAppliesUnitsAxesAndStaleness() {
        val adapter = Mma8452StandardSampleAdapter(
            Mma8452AxisMapping(
                x = AxisTransform(AxisSource.Y, inverted = true),
                y = AxisTransform(AxisSource.X),
                z = AxisTransform(AxisSource.Z),
            ),
        )
        val sample = adapter.fromMetresPerSecondSquared(
            x = Mma8452StandardSampleAdapter.STANDARD_GRAVITY,
            y = Mma8452StandardSampleAdapter.STANDARD_GRAVITY * 2,
            z = -Mma8452StandardSampleAdapter.STANDARD_GRAVITY,
            monotonicMillis = 100,
        )
        val health = Mma8452HealthTracker(staleAfterMillis = 1_000, expectedWhoAmI = 0x2A)

        assertEquals(Mma8452Sample(-2_000, 1_000, -1_000, 100), sample)
        health.connected(0x2A)
        health.sample(100)
        assertEquals(Mma8452Health.AVAILABLE, health.health(1_100))
        assertEquals(Mma8452Health.STALE, health.health(1_101))
        health.ioFailure()
        assertEquals(Mma8452Health.IO_FAILURE, health.health(1_200))
        health.disconnected()
        assertEquals(Mma8452Health.DISCONNECTED, health.health(1_300))
    }

    @Test
    fun mmaIdentityMismatchIsDistinctFromMissingDriver() {
        val health = Mma8452HealthTracker(expectedWhoAmI = 0x2A)

        health.driverMissing()
        assertEquals(Mma8452Health.DRIVER_MISSING, health.health(0))
        health.connected(0x00)
        assertEquals(Mma8452Health.IDENTITY_MISMATCH, health.health(0))
    }
}
