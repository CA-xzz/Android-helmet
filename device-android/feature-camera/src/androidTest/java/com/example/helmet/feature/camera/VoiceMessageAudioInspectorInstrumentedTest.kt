package com.example.helmet.feature.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceMessageAudioInspectorInstrumentedTest {
    @Test
    fun emptyOrContainerlessRecordingIsRejectedAsZeroAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "zero-audio-${System.nanoTime()}.m4a")
        try {
            file.writeBytes(byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))

            assertThrows(Throwable::class.java) {
                VoiceMessageAudioInspector.inspect(file)
            }
        } finally {
            file.delete()
        }
    }
}
