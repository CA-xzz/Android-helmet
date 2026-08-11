package com.example.helmet.location.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpTrackUploadClientTest {
    @Test
    fun endpointRequiresHttpsExceptForLoopbackTests() {
        assertEquals(
            "https://tracks.example.test",
            HttpTrackUploadClient.validateAndNormalizeBaseUrl("https://tracks.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpTrackUploadClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpTrackUploadClient.validateAndNormalizeBaseUrl("http://tracks.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpTrackUploadClient.validateAndNormalizeBaseUrl("https://user:secret@tracks.example.test")
        }
    }
}
