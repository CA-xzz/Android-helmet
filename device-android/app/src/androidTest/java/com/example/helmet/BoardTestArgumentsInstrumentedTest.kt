package com.example.helmet

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardTestArgumentsInstrumentedTest {
    @Test
    fun boardTestArgumentMustBePresentAndNonBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            requireNonBlankBoardTestArgument(null, "backendBearerToken")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNonBlankBoardTestArgument("", "backendBearerToken")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNonBlankBoardTestArgument("   ", "backendBearerToken")
        }
        assertEquals(
            "opaque-random-token",
            requireNonBlankBoardTestArgument("opaque-random-token", "backendBearerToken"),
        )
    }
}
