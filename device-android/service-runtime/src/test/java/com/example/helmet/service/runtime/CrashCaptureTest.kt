package com.example.helmet.service.runtime

import kotlin.system.measureTimeMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashCaptureTest {
    @Test
    fun persistenceWaitHasAHardDeadline() {
        val elapsed = measureTimeMillis {
            assertFalse(
                awaitCrashPersistence(timeoutMillis = 40L) {
                    Thread.sleep(1_000L)
                },
            )
        }

        assertTrue("deadline elapsed=$elapsed", elapsed < 500L)
    }

    @Test
    fun crashPayloadContainsTypesButNotMessagesOrUnsafeThreadNames() {
        val secret = "Bearer production-secret"
        val payload = JSONObject(
            crashPayload(
                threadName = "/private/path/$secret",
                error = IllegalStateException(secret, IllegalArgumentException("password=hidden")),
            ),
        )

        assertFalse(payload.has("thread"))
        assertEquals(IllegalStateException::class.java.name, payload.getString("exception"))
        assertFalse(payload.toString().contains(secret))
        assertFalse(payload.toString().contains("password=hidden"))
    }
}
