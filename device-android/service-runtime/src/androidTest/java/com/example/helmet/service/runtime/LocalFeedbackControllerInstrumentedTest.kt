package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalFeedbackControllerInstrumentedTest {
    @Test
    fun nearElectricVoiceRequestSurvivesMissingVibrator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val feedback = LocalFeedbackController(context)
        try {
            val result = feedback.announceAlarm("NEAR_ELECTRIC", simulated = false)

            assertEquals("检测到近电报警", result.prompt)
            assertTrue(result.vibrationRequested)
            assertFalse(result.vibrationStarted)
        } finally {
            feedback.close()
        }
    }

    @Test
    fun heightVoiceRequestSurvivesMissingVibrator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val feedback = LocalFeedbackController(context)
        try {
            val result = feedback.announceAlarm("HEIGHT_LIMIT", simulated = false)

            assertEquals("检测到高度报警", result.prompt)
            assertTrue(result.vibrationRequested)
            assertFalse(result.vibrationStarted)
        } finally {
            feedback.close()
        }
    }
}
