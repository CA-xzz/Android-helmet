package com.example.helmet.service.runtime

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.hardware.api.SimulatedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallVolumeInstrumentedTest {
    @Test
    fun productionFeedbackChangesCallStreamAndRestoresBoardState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val minimum = audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val originalCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("call volume stream has no adjustable range", maximum > minimum)
        val input = if (originalCallVolume < maximum) {
            SimulatedInput.VOLUME_UP
        } else {
            SimulatedInput.VOLUME_DOWN
        }
        val expected = if (input == SimulatedInput.VOLUME_UP) {
            originalCallVolume + 1
        } else {
            originalCallVolume - 1
        }
        val feedback = LocalFeedbackController(context)
        try {
            assertEquals(expected, feedback.planCallVolumeTarget(input))
            feedback.setCallVolume(expected)
            assertEquals(expected, audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            assertEquals(originalMusicVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalCallVolume, 0)
            feedback.close()
        }
        assertEquals(originalCallVolume, audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    }
}
