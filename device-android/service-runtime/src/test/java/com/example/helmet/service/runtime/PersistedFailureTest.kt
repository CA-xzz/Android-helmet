package com.example.helmet.service.runtime

import com.example.helmet.alert.sync.AlertUploadException
import com.example.helmet.location.sync.TrackUploadException
import com.example.helmet.media.sync.MediaUploadException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PersistedFailureTest {
    @Test
    fun trackFailureRetainsOnlyTypeStatusAndFixedReason() {
        assertSafeFailure(
            error = TrackUploadException(SECRET_MESSAGE, retryable = true, statusCode = 503),
            statusCode = 503,
            expectedType = TrackUploadException::class.java.name,
        )
    }

    @Test
    fun safetyFailureRetainsOnlyTypeStatusAndFixedReason() {
        assertSafeFailure(
            error = AlertUploadException(SECRET_MESSAGE, retryable = false, statusCode = 422),
            statusCode = 422,
            expectedType = AlertUploadException::class.java.name,
        )
    }

    @Test
    fun mediaFailureRetainsOnlyTypeStatusAndFixedReason() {
        assertSafeFailure(
            error = MediaUploadException(SECRET_MESSAGE, retryable = false, statusCode = 409),
            statusCode = 409,
            expectedType = MediaUploadException::class.java.name,
        )
    }

    @Test
    fun unexpectedFailureDoesNotPersistMessageOrCause() {
        val cause = IllegalArgumentException(SECRET_MESSAGE)
        val failure = persistedFailure(
            IllegalStateException(SECRET_MESSAGE, cause),
            statusCode = null,
            reason = "UNEXPECTED_TRANSPORT_FAILURE",
        )

        val stored = failure.asStorageText()
        assertEquals(IllegalStateException::class.java.name, JSONObject(stored).getString("errorType"))
        assertEquals(JSONObject.NULL, JSONObject(stored).get("statusCode"))
        assertFalse(stored.contains(SECRET_TOKEN))
        assertFalse(stored.contains(SECRET_URL))
        assertFalse(stored.contains(SECRET_PATH))
        assertFalse(stored.contains(SECRET_COORDINATES))
    }

    @Test
    fun invalidTransportStatusIsOmittedInsteadOfBreakingFailurePersistence() {
        val failure = persistedFailure(
            IllegalStateException(SECRET_MESSAGE),
            statusCode = Int.MAX_VALUE,
            reason = "UNEXPECTED_TRANSPORT_FAILURE",
        )

        assertEquals(JSONObject.NULL, JSONObject(failure.asStorageText()).get("statusCode"))
    }

    private fun assertSafeFailure(error: Throwable, statusCode: Int, expectedType: String) {
        val failure = persistedFailure(error, statusCode, "RETRYABLE_TRANSPORT_FAILURE")
        val roomValue = failure.asStorageText()
        val eventValue = JSONObject(failure.toEventFields()).toString()

        listOf(roomValue, eventValue).forEach { stored ->
            val json = JSONObject(stored)
            assertEquals(expectedType, json.getString("errorType"))
            assertEquals(statusCode, json.getInt("statusCode"))
            assertEquals("RETRYABLE_TRANSPORT_FAILURE", json.getString("reason"))
            assertFalse(stored.contains(SECRET_TOKEN))
            assertFalse(stored.contains(SECRET_URL))
            assertFalse(stored.contains(SECRET_PATH))
            assertFalse(stored.contains(SECRET_COORDINATES))
        }
    }

    companion object {
        private const val SECRET_TOKEN = "secret-token-value"
        private const val SECRET_URL = "https://backend.invalid/private"
        private const val SECRET_PATH = "/data/user/0/com.example.helmet/files/media/private.jpg"
        private const val SECRET_COORDINATES = "31.234567,121.456789"
        private const val SECRET_MESSAGE =
            "Bearer $SECRET_TOKEN at $SECRET_URL path=$SECRET_PATH coordinates=$SECRET_COORDINATES"
    }
}
