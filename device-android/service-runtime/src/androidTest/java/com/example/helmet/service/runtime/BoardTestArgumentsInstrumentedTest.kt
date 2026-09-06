package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardTestArgumentsInstrumentedTest {
    @Test
    fun backendTokenMustBePresentAndNonBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            requireBoardBackendBearerToken(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireBoardBackendBearerToken("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireBoardBackendBearerToken("   ")
        }
        assertEquals("opaque-random-token", requireBoardBackendBearerToken("opaque-random-token"))
    }
}
