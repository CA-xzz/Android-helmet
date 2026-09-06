package com.example.helmet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationProcessPolicyTest {
    @Test
    fun exactApplicationProcessIsInitialized() {
        assertTrue(ApplicationProcessPolicy.shouldInitialize("com.example.helmet", "com.example.helmet"))
    }

    @Test
    fun secondaryAndUnknownProcessesAreNotInitialized() {
        assertFalse(ApplicationProcessPolicy.shouldInitialize("com.example.helmet:hardware", "com.example.helmet"))
        assertFalse(ApplicationProcessPolicy.shouldInitialize("com.example.helmet.worker", "com.example.helmet"))
        assertFalse(ApplicationProcessPolicy.shouldInitialize(null, "com.example.helmet"))
    }

    @Test
    fun runtimeServiceStartSuccessReturnsNoFailure() {
        var invoked = false

        val failure = ApplicationProcessPolicy.attemptRuntimeServiceStart { invoked = true }

        assertTrue(invoked)
        assertNull(failure)
    }

    @Test
    fun runtimeServiceStartFailureIsContainedForBackgroundProcessStartup() {
        val expected = IllegalStateException("foreground service start is not allowed")

        val failure = ApplicationProcessPolicy.attemptRuntimeServiceStart { throw expected }

        assertSame(expected, failure)
    }
}
