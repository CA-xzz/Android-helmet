package com.example.helmet.service.runtime

import com.example.helmet.communication.sync.CommunicationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationFailurePrivacyTest {
    @Test
    fun durableFailureCodeDoesNotContainExceptionMessageOrCredentials() {
        val failure = CommunicationException(
            message = "Bearer secret-token https://person@example.invalid/private",
            retryable = false,
            statusCode = 403,
        )

        val code = communicationFailureCode(failure)

        assertTrue(code.contains("status=403"))
        assertTrue(code.contains("retryable=false"))
        assertFalse(code.contains("secret-token"))
        assertFalse(code.contains("example.invalid"))
    }
}
