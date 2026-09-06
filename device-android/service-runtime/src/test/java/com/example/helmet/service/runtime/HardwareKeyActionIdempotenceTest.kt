package com.example.helmet.service.runtime

import com.example.helmet.data.local.HardwareKeyActionState
import com.example.helmet.data.local.HardwareKeyAction
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.hardware.api.HardwareCommandException
import com.example.helmet.hardware.api.HardwareCommandOutcome
import com.example.helmet.hardware.api.HardwareCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyActionIdempotenceTest {
    @Test
    fun hardwareEventIdentityIsGlobalAcrossKeyTypes() {
        val photo = hardwareKeyActionId("device-1", SimulatedInput.PHOTO_SHORT, 10, 77)
        val sos = hardwareKeyActionId("device-1", SimulatedInput.SOS, 11, 77)
        assertEquals("device-1:key:77", photo)
        assertEquals(photo, sos)
        assertEquals(
            "device-1:key:sim:PHOTO_SHORT:10",
            hardwareKeyActionId("device-1", SimulatedInput.PHOTO_SHORT, 10, null),
        )
        val existing = HardwareKeyAction(
            actionId = photo,
            input = SimulatedInput.PHOTO_SHORT.name,
            plannedAction = "PHOTO",
            rejectionReason = null,
            targetCallId = null,
            simulated = false,
            monotonicMillis = 10,
            hardwareEventId = 77,
            sequence = 9,
            receivedAtEpochMillis = 100,
        )
        // The replay's wire sequence is deliberately absent from business identity validation.
        requireMatchingHardwareKeyEvent(existing, SimulatedInput.PHOTO_SHORT, 10, 77, false)
        assertThrows(IllegalArgumentException::class.java) {
            requireMatchingHardwareKeyEvent(existing, SimulatedInput.SOS, 10, 77, false)
        }
    }

    @Test
    fun processDeathAtCommitBoundaryReplaysTheSameEffectAndDefersFeedback() {
        val assets = mutableSetOf<String>()
        var state = HardwareKeyActionState.RECEIVED
        var feedbackCount = 0

        assertThrows(RetryableHardwareKeyActionException::class.java) {
            runBlocking {
                runDurableActionAttempt(
                    performSideEffect = { assets += "photo:device:key:PHOTO_SHORT:7" },
                    markApplied = { throw IllegalStateException("injected commit failure") },
                    readState = { state },
                    afterApplied = { feedbackCount++ },
                )
            }
        }
        assertEquals(setOf("photo:device:key:PHOTO_SHORT:7"), assets)
        assertEquals(0, feedbackCount)

        runBlocking {
            runDurableActionAttempt(
                performSideEffect = { assets += "photo:device:key:PHOTO_SHORT:7" },
                markApplied = {
                    state = HardwareKeyActionState.APPLIED
                    true
                },
                readState = { state },
                afterApplied = { feedbackCount++ },
            )
        }
        assertEquals(1, assets.size)
        assertEquals(1, feedbackCount)
    }

    @Test
    fun lostCommitResponseAcceptsAppliedButRetriesReceived() = runBlocking {
        var feedbackCount = 0
        runDurableActionAttempt(
            performSideEffect = {},
            markApplied = { false },
            readState = { HardwareKeyActionState.APPLIED },
            afterApplied = { feedbackCount++ },
        )
        assertEquals(1, feedbackCount)

        assertThrows(RetryableHardwareKeyActionException::class.java) {
            runBlocking {
                runDurableActionAttempt(
                    performSideEffect = {},
                    markApplied = { false },
                    readState = { HardwareKeyActionState.RECEIVED },
                    afterApplied = { feedbackCount++ },
                )
            }
        }
        assertEquals(1, feedbackCount)
    }

    @Test
    fun plannedVolumeIsAbsoluteAndReplayDoesNotIncrementTwice() {
        var volume = 3
        val target = requireNotNull(
            planAbsoluteCallVolume(SimulatedInput.VOLUME_UP, volume, minimum = 0, maximum = 10),
        )
        repeat(2) { volume = target }
        assertEquals(4, volume)
        assertEquals(0, planAbsoluteCallVolume(SimulatedInput.VOLUME_DOWN, 0, 0, 10))
        assertEquals(10, planAbsoluteCallVolume(SimulatedInput.VOLUME_UP, 10, 0, 10))
    }

    @Test
    fun sosUsesThePersistentHardwareEventIdInsteadOfWrappingMonotonicTime() {
        val first = sosAlarmBusinessId(101, "ignored")
        val second = sosAlarmBusinessId(102, "ignored")
        assertEquals(101L, first)
        assertEquals(102L, second)
        assertEquals(first, sosAlarmBusinessId(101, "different replay metadata"))

        val simulated = sosAlarmBusinessId(null, "device:key:sim:SOS:77")
        assertEquals(simulated, sosAlarmBusinessId(null, "device:key:sim:SOS:77"))
    }

    @Test
    fun sosReusesAnyExistingActiveCallInsteadOfCreatingThePlannedCallAgain() {
        assertTrue(shouldCreateSosEmergencyCall(null))
        assertFalse(shouldCreateSosEmergencyCall("active-call"))
        assertEquals("fallback-call", planSosFallbackCallId { "fallback-call" })
    }

    @Test
    fun hardwareKeyRetryUsesBackoffAndTreatsNegativeAckAsTerminal() {
        assertEquals(1_000, hardwareKeyActionRetryDelayMillis(0))
        assertEquals(30_000, hardwareKeyActionRetryDelayMillis(20))
        assertTrue(isRetryableHardwareCommandFailure(commandFailure(HardwareCommandOutcome.SEND_FAILED)))
        assertTrue(isRetryableHardwareCommandFailure(commandFailure(HardwareCommandOutcome.TIMED_OUT)))
        assertFalse(isRetryableHardwareCommandFailure(commandFailure(HardwareCommandOutcome.REJECTED)))
    }

    @Test
    fun sosAlarmPersistenceFailureKeepsTheDurableKeyActionRetryable() {
        assertThrows(RetryableHardwareKeyActionException::class.java) {
            requirePersistedSosAlarm(false)
        }
        requirePersistedSosAlarm(true)
    }

    @Test
    fun callStartEndAndSosStorageFailuresRemainRetryable() = runBlocking {
        listOf("CALL_START", "CALL_END", "SOS").forEach { action ->
            var attempts = 0
            assertThrows(RetryableHardwareKeyActionException::class.java) {
                runBlocking {
                    runRetryableCallPersistence {
                        attempts += 1
                        error("injected $action Room failure")
                    }
                }
            }
            val result = runRetryableCallPersistence {
                attempts += 1
                "$action-applied"
            }
            assertEquals("$action-applied", result)
            assertEquals(2, attempts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                runRetryableCallPersistence<Unit> { throw IllegalArgumentException("identity collision") }
            }
        }
        Unit
    }

    @Test
    fun outboxSchedulingPrecedesFalliblePostCommitSideEffects() = runBlocking {
        val order = mutableListOf<String>()
        val committed = commitCallOutboxAndSchedule(
            commit = { order += "room-commit"; "call" },
            schedule = { order += "worker-enqueued" },
        )
        val sideEffect = runCatching { order += "audit"; error("injected audit failure") }

        assertEquals("call", committed)
        assertTrue(sideEffect.isFailure)
        assertEquals(listOf("room-commit", "worker-enqueued", "audit"), order)
    }

    private fun commandFailure(outcome: HardwareCommandOutcome) = HardwareCommandException(
        HardwareCommandResult(
            outcome = outcome,
            type = 0x40,
            businessSequence = 1,
            wireSequence = 2,
            resultCode = if (outcome == HardwareCommandOutcome.REJECTED) 4 else null,
        ),
    )
}
