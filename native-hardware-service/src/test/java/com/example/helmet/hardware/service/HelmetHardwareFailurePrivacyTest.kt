package com.example.helmet.hardware.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HelmetHardwareFailurePrivacyTest {
    @Test
    fun serialReadFailureCodeDoesNotPropagateThrowableMessage() {
        val secret = "Bearer production-secret-token /private/media/path"

        val code = serialReadFailureCode(IllegalStateException(secret))

        assertEquals("SERIAL_READ_FAILED:java.lang.IllegalStateException", code)
        assertFalse(code.contains(secret))
        assertFalse(code.contains("Bearer"))
        assertFalse(code.contains("/private/media/path"))
    }
}
