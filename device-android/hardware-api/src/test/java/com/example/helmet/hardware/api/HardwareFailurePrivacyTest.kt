package com.example.helmet.hardware.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareFailurePrivacyTest {
    @Test
    fun statusFailureCodeDoesNotExposeExceptionMessage() {
        val code = hardwareFailureCode(IllegalStateException("/dev/ttyS9 token=secret"))

        assertTrue(code.contains("IllegalStateException"))
        assertFalse(code.contains("ttyS9"))
        assertFalse(code.contains("secret"))
    }
}
