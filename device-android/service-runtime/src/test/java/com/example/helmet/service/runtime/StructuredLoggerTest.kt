package com.example.helmet.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredLoggerTest {
    @Test
    fun throwableMessagesAndCredentialsAreNotCopiedIntoStructuredFields() {
        val secret = "Bearer production-secret-token"
        val error = IllegalStateException(secret, IllegalArgumentException("password=hidden"))

        val fields = StructuredLogger.sanitizedErrorFields(error)
        val rendered = fields.toString()

        assertEquals(IllegalStateException::class.java.name, fields["errorType"])
        assertTrue((fields["causeTypes"] as List<*>).contains(IllegalArgumentException::class.java.name))
        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("password=hidden"))
    }

    @Test
    fun stackAndCauseMetadataAreBounded() {
        var error: Throwable = IllegalStateException("root")
        repeat(20) { error = IllegalStateException("cause-$it", error) }
        error.stackTrace = Array(100) { index ->
            StackTraceElement("Class$index", "method", "Source.kt", index + 1)
        }

        val fields = StructuredLogger.sanitizedErrorFields(error)

        assertEquals(8, (fields["causeTypes"] as List<*>).size)
        assertEquals(32, (fields["stack"] as List<*>).size)
    }

    @Test
    fun callerFieldsRedactCredentialsPathsUrisAndCoordinatesRecursively() {
        val fields = StructuredLogger.sanitizedFields(
            mapOf(
                "backendBearerToken" to "production-token",
                "filePath" to "/data/user/0/private/video.mp4",
                "latitude" to 31.2304,
                "detail" to "Authorization: Bearer hidden",
                "pathDetail" to "failed while reading /data/user/0/private/file",
                "nested" to mapOf(
                    "longitude" to 121.4737,
                    "endpoint" to "https://user:password@example.invalid/path",
                ),
                "safe" to "READY",
            ),
        )
        val rendered = fields.toString()

        assertEquals("READY", fields["safe"])
        assertFalse(rendered.contains("production-token"))
        assertFalse(rendered.contains("/data/user"))
        assertFalse(rendered.contains("31.2304"))
        assertFalse(rendered.contains("121.4737"))
        assertFalse(rendered.contains("example.invalid"))
        assertFalse(rendered.contains("Bearer hidden"))
        assertFalse(rendered.contains("/data/user"))
    }
}
