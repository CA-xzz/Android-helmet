package com.example.helmet.feature.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.VoiceMessageSenderRole
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceMessageRecorderProductionInstrumentedTest {
    @Test
    fun productionRecorderEitherCommitsSampledAudioOrFailsWithoutFalseAsset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaStore = MediaStore(HelmetDatabase.get(context))
        val deviceId = "voice-board-${UUID.randomUUID()}"
        val eventId = "event-${UUID.randomUUID()}"
        val recorder = AndroidVoiceMessageRecorder(context, mediaStore, deviceId)
        try {
            val started = runCatching { recorder.start(eventId) }
            if (started.isFailure) {
                assertTrue(started.exceptionOrNull() is CameraOperationException)
                assertTrue(mediaStore.findByRelatedEventAndKind(deviceId, eventId, MediaKind.VOICE) == null)
                println("VOICE_CAPTURE_RESULT=UNAVAILABLE_AT_START")
                return@runBlocking
            }
            delay(RECORDING_MILLIS)
            val stopped = runCatching { recorder.stop() }
            if (stopped.isFailure) {
                assertTrue(stopped.exceptionOrNull() is CameraOperationException)
                assertTrue(mediaStore.findByRelatedEventAndKind(deviceId, eventId, MediaKind.VOICE) == null)
                println("VOICE_CAPTURE_RESULT=NO_COMPLETE_AUDIO")
                return@runBlocking
            }

            val asset = stopped.getOrThrow()
            assertEquals(MediaKind.VOICE, asset.kind)
            assertEquals(VoiceMessageSenderRole.DEVICE, asset.voiceSenderRole)
            assertTrue(requireNotNull(asset.durationMillis) > 0)
            assertTrue(asset.byteSize > 0)
            assertEquals(64, asset.sha256.length)
            assertFalse(asset.voiceAllowedRoles.isNullOrEmpty())
            assertTrue(File(asset.filePath).isFile)
            assertNotNull(mediaStore.find(asset.assetId))
            println("VOICE_CAPTURE_RESULT=COMMITTED_SAMPLE_AUDIO")
        } finally {
            recorder.close()
        }
    }

    private companion object {
        const val RECORDING_MILLIS = 1_500L
    }
}
