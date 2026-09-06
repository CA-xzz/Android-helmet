package com.example.helmet.service.runtime

import com.example.helmet.communication.sync.CommunicationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CallNegotiationTrackerTest {
    @Test
    fun recoveryOfferSupersedesOldCursorAndRequiresANewAnswer() {
        val tracker = CallNegotiationTracker(initialServerCursor = 7)
        val firstGeneration = tracker.beginLocalIceGeneration()
        tracker.recordOffer(
            sequence = 8,
            localIceGeneration = firstGeneration,
            nowMonotonicMillis = 1_000,
            answerTimeoutMillis = 5_000,
        )
        assertTrue(tracker.snapshot().awaitingAnswer)
        assertFalse(tracker.answerTimedOut(5_999))
        assertTrue(tracker.answerTimedOut(6_000))

        tracker.recordRemote(9)
        assertTrue(tracker.shouldApplyAnswer(9))
        tracker.recordAnswer(9)
        assertFalse(tracker.snapshot().awaitingAnswer)
        assertFalse(tracker.shouldApplyAnswer(10))

        val restartGeneration = tracker.beginLocalIceGeneration()
        tracker.recordOffer(
            sequence = 10,
            localIceGeneration = restartGeneration,
            nowMonotonicMillis = 7_000,
            answerTimeoutMillis = 5_000,
        )
        assertTrue(tracker.shouldApplyAnswer(11))
        assertEquals(1, tracker.recordRestartAttempt())
        assertEquals(2, tracker.recordRestartAttempt())
    }

    @Test
    fun rejectsRegressedOrDuplicateServerSequence() {
        val tracker = CallNegotiationTracker(initialServerCursor = 3)
        val generation = tracker.beginLocalIceGeneration()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.recordOffer(3, generation, 1_000, 5_000)
        }
        tracker.recordOffer(4, generation, 1_000, 5_000)
        assertThrows(IllegalArgumentException::class.java) { tracker.recordRemote(4) }
    }

    @Test
    fun transientSignalingFailureUsesCappedBackoffAndEventuallySucceeds() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val observedAttempts = mutableListOf<Int>()

        val result = retryTransientCommunication(
            initialDelayMillis = 100,
            maximumDelayMillis = 200,
            pause = { delays += it },
            onRetry = { attempt, _, _, _ -> observedAttempts += attempt },
        ) {
            attempts += 1
            if (attempts <= 3) throw CommunicationException("temporary", retryable = true, statusCode = 503)
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(4, attempts)
        assertEquals(listOf(100L, 200L, 200L), delays)
        assertEquals(listOf(1, 2, 3), observedAttempts)
    }

    @Test
    fun permanentSignalingFailureIsNotRetried() = runBlocking {
        var attempts = 0
        var pauses = 0

        val error = assertThrows(CommunicationException::class.java) {
            runBlocking {
                retryTransientCommunication(
                    pause = { pauses += 1 },
                ) {
                    attempts += 1
                    throw CommunicationException("invalid response", retryable = false, statusCode = 400)
                }
            }
        }

        assertFalse(error.retryable)
        assertEquals(1, attempts)
        assertEquals(0, pauses)
    }

    @Test
    fun transientSignalingFailureStopsAtConfiguredAttemptLimit() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val error = assertThrows(CommunicationException::class.java) {
            runBlocking {
                retryTransientCommunication(
                    maximumAttempts = 3,
                    pause = { delays += it },
                ) {
                    attempts += 1
                    throw CommunicationException("offline", retryable = true, statusCode = 503)
                }
            }
        }

        assertTrue(error.retryable)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_000L), delays)
    }

    @Test
    fun retryDelayDoesNotConsumeAnswerTimeoutAndCancellationStopsRetry() = runBlocking {
        val tracker = CallNegotiationTracker(0)
        tracker.recordOffer(
            sequence = 1,
            localIceGeneration = tracker.beginLocalIceGeneration(),
            nowMonotonicMillis = 1_000,
            answerTimeoutMillis = 5_000,
        )
        tracker.pauseAnswerTimeout(2_000)
        assertFalse(tracker.answerTimedOut(7_999))
        assertTrue(tracker.answerTimedOut(8_000))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryTransientCommunication(
                    pause = { throw CancellationException("call ended") },
                ) {
                    throw CommunicationException("offline", retryable = true)
                }
            }
        }
        Unit
    }

    @Test
    fun retryAuditIsExponentiallySampled() {
        assertEquals(
            listOf(1, 2, 4, 8, 16),
            (1..16).filter(::shouldRecordSignalingRetry),
        )
    }

    @Test
    fun staleCallSessionCancelsRetryBeforeSleepingOrSendingAgain() {
        var active = true
        var attempts = 0
        var pauses = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryTransientCommunication(
                    shouldContinue = { active },
                    pause = { pauses += 1 },
                ) {
                    attempts += 1
                    active = false
                    throw CommunicationException("offline", retryable = true)
                }
            }
        }
        assertEquals(1, attempts)
        assertEquals(0, pauses)
    }

    @Test
    fun answerAndIceGenerationMustBeExplicitAndMatchTheLatestOffer() {
        val tracker = CallNegotiationTracker(0)
        tracker.recordOffer(
            sequence = 10,
            localIceGeneration = tracker.beginLocalIceGeneration(),
            nowMonotonicMillis = 1_000,
            answerTimeoutMillis = 5_000,
        )
        val current = requireSignalOfferSequence(JSONObject().put("offerSequence", 10))
        val stale = requireSignalOfferSequence(JSONObject().put("offerSequence", 9))

        assertEquals(tracker.snapshot().latestOfferSequence, current)
        assertFalse(tracker.snapshot().latestOfferSequence == stale)
        assertThrows(IllegalArgumentException::class.java) {
            requireSignalOfferSequence(JSONObject())
        }
    }

    @Test
    fun delayedOldIceCallbacksCannotBeRelabelledAsRestartIce() {
        val tracker = CallNegotiationTracker(0)
        val firstGeneration = tracker.beginLocalIceGeneration()
        tracker.recordOffer(10, firstGeneration, 1_000, 5_000)
        assertEquals(10L, tracker.offerSequenceForLocalIceGeneration(firstGeneration))

        val restartGeneration = tracker.beginLocalIceGeneration()
        assertEquals(null, tracker.offerSequenceForLocalIceGeneration(firstGeneration))
        assertEquals(null, tracker.offerSequenceForLocalIceGeneration(restartGeneration))
        tracker.recordOffer(11, restartGeneration, 2_000, 5_000)

        assertEquals(null, tracker.offerSequenceForLocalIceGeneration(firstGeneration))
        assertEquals(11L, tracker.offerSequenceForLocalIceGeneration(restartGeneration))
    }

    @Test
    fun answerDeadlineSaturatesInsteadOfOverflowing() {
        val tracker = CallNegotiationTracker(0)
        tracker.recordOffer(
            sequence = 1,
            localIceGeneration = tracker.beginLocalIceGeneration(),
            nowMonotonicMillis = Long.MAX_VALUE - 5,
            answerTimeoutMillis = 10,
        )

        assertFalse(tracker.answerTimedOut(Long.MAX_VALUE - 1))
        assertTrue(tracker.answerTimedOut(Long.MAX_VALUE))
    }
}
