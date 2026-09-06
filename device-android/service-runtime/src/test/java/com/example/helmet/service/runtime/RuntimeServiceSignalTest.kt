package com.example.helmet.service.runtime

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceSignalTest {
    @Test
    fun successfulSignalReturnsNoFailure() {
        var invoked = false

        val failure = attemptRuntimeServiceSignal { invoked = true }

        assertTrue(invoked)
        assertNull(failure)
    }

    @Test
    fun rejectedBackgroundForegroundServiceStartIsContained() {
        val expected = IllegalStateException("foreground service start is not allowed")

        val failure = attemptRuntimeServiceSignal { throw expected }

        assertSame(expected, failure)
    }
}
