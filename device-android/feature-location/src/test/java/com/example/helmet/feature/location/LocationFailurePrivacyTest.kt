package com.example.helmet.feature.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFailurePrivacyTest {
    @Test
    fun statusFailureCodeDoesNotExposeProviderExceptionMessage() {
        val code = locationFailureCode(SecurityException("person location denied token=secret"))

        assertTrue(code.contains("SecurityException"))
        assertFalse(code.contains("person location"))
        assertFalse(code.contains("secret"))
    }
}
