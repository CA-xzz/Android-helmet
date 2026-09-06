package com.example.helmet.hardware.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCallbackBarrierTest {
    @Test
    fun decodedOldFrameAndFaultCannotDispatchAfterReplacementSessionIsPublished() {
        val oldSession = Any()
        val replacementSession = Any()
        val currentSession = AtomicReference<Any?>(oldSession)
        val oldBarrier = SessionCallbackBarrier()
        val replacementBarrier = SessionCallbackBarrier()
        val oldFrameCallbacks = AtomicInteger()
        val oldFaultCallbacks = AtomicInteger()
        val replacementFrameCallbacks = AtomicInteger()
        val replacementLinkCallbacks = AtomicInteger()
        val oldNotificationsReady = CountDownLatch(2)
        val releaseOldNotifications = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldFrame = executor.submit<Boolean> {
                oldNotificationsReady.countDown()
                assertTrue(releaseOldNotifications.await(5, TimeUnit.SECONDS))
                oldBarrier.dispatchIfCurrent(oldSession, currentSession::get) {
                    oldFrameCallbacks.incrementAndGet()
                }
            }
            val oldFault = executor.submit<Boolean> {
                oldNotificationsReady.countDown()
                assertTrue(releaseOldNotifications.await(5, TimeUnit.SECONDS))
                oldBarrier.dispatchIfCurrent(oldSession, currentSession::get) {
                    oldFaultCallbacks.incrementAndGet()
                }
            }
            assertTrue(oldNotificationsReady.await(5, TimeUnit.SECONDS))

            currentSession.set(null)
            oldBarrier.invalidate()
            oldBarrier.awaitDrained()
            currentSession.set(replacementSession)

            assertTrue(
                replacementBarrier.dispatchIfCurrent(
                    replacementSession,
                    currentSession::get,
                ) { replacementFrameCallbacks.incrementAndGet() },
            )
            assertTrue(
                replacementBarrier.dispatchIfCurrent(
                    replacementSession,
                    currentSession::get,
                ) { replacementLinkCallbacks.incrementAndGet() },
            )

            releaseOldNotifications.countDown()
            assertFalse(oldFrame.get(5, TimeUnit.SECONDS))
            assertFalse(oldFault.get(5, TimeUnit.SECONDS))
            assertEquals(0, oldFrameCallbacks.get())
            assertEquals(0, oldFaultCallbacks.get())
            assertEquals(1, replacementFrameCallbacks.get())
            assertEquals(1, replacementLinkCallbacks.get())
        } finally {
            releaseOldNotifications.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun invalidationWaitsForAnAdmittedCallbackBeforeReplacementCanProceed() {
        val oldSession = Any()
        val replacementSession = Any()
        val currentSession = AtomicReference<Any?>(oldSession)
        val oldBarrier = SessionCallbackBarrier()
        val oldCallbackEntered = CountDownLatch(1)
        val releaseOldCallback = CountDownLatch(1)
        val drainStarted = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldCallback = executor.submit<Boolean> {
                oldBarrier.dispatchIfCurrent(oldSession, currentSession::get) {
                    oldCallbackEntered.countDown()
                    assertTrue(releaseOldCallback.await(5, TimeUnit.SECONDS))
                    callbacks.incrementAndGet()
                }
            }
            assertTrue(oldCallbackEntered.await(5, TimeUnit.SECONDS))

            currentSession.set(null)
            oldBarrier.invalidate()
            val drain = executor.submit {
                drainStarted.countDown()
                oldBarrier.awaitDrained()
            }
            assertTrue(drainStarted.await(5, TimeUnit.SECONDS))
            assertFalse(drain.isDone)
            assertFalse(
                oldBarrier.dispatchIfCurrent(oldSession, currentSession::get) {
                    throw AssertionError("invalidated callback was dispatched")
                },
            )

            releaseOldCallback.countDown()
            assertTrue(oldCallback.get(5, TimeUnit.SECONDS))
            drain.get(5, TimeUnit.SECONDS)
            currentSession.set(replacementSession)
            assertEquals(1, callbacks.get())
        } finally {
            releaseOldCallback.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun callbackActionDoesNotHoldTheBarrierMonitor() {
        val barrier = SessionCallbackBarrier()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(
                barrier.dispatch {
                    executor.submit { barrier.invalidate() }.get(5, TimeUnit.SECONDS)
                },
            )
            assertFalse(barrier.dispatch { throw AssertionError("invalidated barrier dispatched") })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
