package com.example.helmet.hardware.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTransportEpochTest {
    @Test
    fun successfulReconnectCannotRetryUntilDetachedTransportCleanupRequestsIt() {
        val gate = ProviderReconnectAfterCleanupGate()

        // A job that successfully published a transport must not infer a retry merely because the
        // transport was detached before that job observed the shared remote reference.
        assertFalse(gate.consume(canReconnect = true))

        // Death or rotation records this request only after its drain and close sequence finishes.
        gate.request()
        assertTrue(gate.consume(canReconnect = true))
        assertFalse(gate.consume(canReconnect = true))

        gate.request()
        assertFalse(gate.consume(canReconnect = false))
        assertFalse(gate.consume(canReconnect = true))
    }

    @Test
    fun providerTransportAllowsOnlyOnePortOpenAttempt() {
        val attempted = AtomicBoolean()

        assertTrue(claimProviderPortOpenAttempt(attempted))
        assertFalse(claimProviderPortOpenAttempt(attempted))
    }

    @Test
    fun reconnectRejectsEveryOldCallbackSideEffectAndAcceptsCurrentCallback() {
        val statusUpdates = AtomicInteger()
        val deliveredEvents = AtomicInteger()
        val acknowledgements = AtomicInteger()
        val oldEpoch = ProviderTransportEpoch()
        val currentEpoch = ProviderTransportEpoch()

        assertFalse(
            oldEpoch.dispatch {
                statusUpdates.incrementAndGet()
                deliveredEvents.incrementAndGet()
                acknowledgements.incrementAndGet()
            },
        )

        oldEpoch.activate()
        assertTrue(
            oldEpoch.dispatch {
                statusUpdates.incrementAndGet()
                deliveredEvents.incrementAndGet()
                acknowledgements.incrementAndGet()
            },
        )
        oldEpoch.invalidate()
        currentEpoch.activate()

        assertFalse(
            oldEpoch.dispatch {
                statusUpdates.incrementAndGet()
                deliveredEvents.incrementAndGet()
                acknowledgements.incrementAndGet()
            },
        )
        assertTrue(
            currentEpoch.dispatch {
                statusUpdates.incrementAndGet()
                deliveredEvents.incrementAndGet()
                acknowledgements.incrementAndGet()
            },
        )
        assertEquals(2, statusUpdates.get())
        assertEquals(2, deliveredEvents.get())
        assertEquals(2, acknowledgements.get())
    }

    @Test
    fun pausedStopDrainsInFlightSendAndAcknowledgementBeforeItCanComplete() {
        val epoch = ProviderTransportEpoch().also { it.activate() }
        val sendEntered = CountDownLatch(1)
        val acknowledgementEntered = CountDownLatch(1)
        val releaseOperations = CountDownLatch(1)
        val drainStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val send = executor.submit<Boolean> {
                epoch.dispatch {
                    sendEntered.countDown()
                    assertTrue(releaseOperations.await(5, TimeUnit.SECONDS))
                }
            }
            val acknowledgement = executor.submit<Boolean> {
                epoch.dispatch {
                    acknowledgementEntered.countDown()
                    assertTrue(releaseOperations.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(sendEntered.await(5, TimeUnit.SECONDS))
            assertTrue(acknowledgementEntered.await(5, TimeUnit.SECONDS))
            epoch.invalidate()
            val drain = executor.submit {
                drainStarted.countDown()
                epoch.awaitDrained()
            }
            assertTrue(drainStarted.await(5, TimeUnit.SECONDS))
            assertFalse(drain.isDone)
            assertFalse(epoch.dispatch { throw AssertionError("stale send was admitted") })

            releaseOperations.countDown()
            assertTrue(send.get(5, TimeUnit.SECONDS))
            assertTrue(acknowledgement.get(5, TimeUnit.SECONDS))
            drain.get(5, TimeUnit.SECONDS)
            assertTrue(drain.isDone)
        } finally {
            releaseOperations.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedOldSendAndAcknowledgementCannotBorrowReplacementTransportAfterResume() = runBlocking {
        val sendMutex = Mutex(locked = true)
        val oldTransport = ProviderTransportEpoch().also { it.activate() }
        val replacementTransport = ProviderTransportEpoch().also { it.activate() }
        val currentTransport = AtomicReference<ProviderTransportEpoch?>(oldTransport)
        val replacementWrites = AtomicInteger()
        val replacementAcknowledgements = AtomicInteger()

        val queuedSend = async(start = CoroutineStart.UNDISPATCHED) {
            val requestedTransport = oldTransport
            sendMutex.withLock {
                if (!isCurrentProviderTransportRequest(requestedTransport, currentTransport.get())) {
                    return@withLock false
                }
                requestedTransport.dispatch { replacementWrites.incrementAndGet() }
            }
        }
        val queuedAcknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
            val requestedTransport = oldTransport
            sendMutex.withLock {
                if (!isCurrentProviderTransportRequest(requestedTransport, currentTransport.get())) {
                    return@withLock false
                }
                requestedTransport.dispatch { replacementAcknowledgements.incrementAndGet() }
            }
        }

        currentTransport.set(null)
        oldTransport.invalidate()
        val stopBarrier = async(start = CoroutineStart.UNDISPATCHED) {
            sendMutex.withLock { Unit }
        }
        assertFalse(stopBarrier.isCompleted)

        // Exercise the stronger scheduling race: replacement publication happens before the old
        // mutex waiters are resumed. Their captured identity must still reject both operations.
        currentTransport.set(replacementTransport)
        sendMutex.unlock()

        assertFalse(queuedSend.await())
        assertFalse(queuedAcknowledgement.await())
        stopBarrier.await()
        assertEquals(0, replacementWrites.get())
        assertEquals(0, replacementAcknowledgements.get())
    }

    @Test
    fun queuedOldEventCannotAcknowledgeRotatedPortTransportButSameSequenceRetryCan() = runBlocking {
        val oldEpoch = ProviderTransportEpoch().also { it.activate() }
        val replacementEpoch = ProviderTransportEpoch().also { it.activate() }
        val currentEpoch = AtomicReference<ProviderTransportEpoch?>(oldEpoch)
        val sendMutex = Mutex()
        val writes = AtomicInteger()
        val sequence = 73
        val queuedEvent = HardwareEvent.Key(
            monotonicMillis = 1L,
            input = SimulatedInput.SOS,
            eventId = 9L,
            sequence = sequence,
            acknowledgement = HardwareAcknowledgement(sequence, oldEpoch),
        )
        val queue = HardwareEventQueue(capacity = 1)
        assertTrue(queue.offer(listOf(queuedEvent)).accepted)
        val persistedOldEvent = queue.events.first() as HardwareEvent.Key

        suspend fun acknowledge(token: HardwareAcknowledgement): Boolean = sendMutex.withLock {
            val requestedEpoch = currentEpoch.get() ?: return@withLock false
            if (token.transportEpoch !== requestedEpoch || requestedEpoch !== currentEpoch.get()) {
                return@withLock false
            }
            requestedEpoch.dispatch { writes.incrementAndGet() }
        }

        oldEpoch.invalidate()
        currentEpoch.set(replacementEpoch)
        assertFalse(acknowledge(requireNotNull(persistedOldEvent.acknowledgement)))
        assertEquals(0, writes.get())

        val retriedEvent = persistedOldEvent.copy(
            acknowledgement = HardwareAcknowledgement(sequence, replacementEpoch),
        )
        assertTrue(acknowledge(requireNotNull(retriedEvent.acknowledgement)))
        assertEquals(1, writes.get())
    }
}
