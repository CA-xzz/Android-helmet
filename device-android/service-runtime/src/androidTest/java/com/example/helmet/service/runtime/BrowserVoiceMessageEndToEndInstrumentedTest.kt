package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.media.sync.MediaIntegrity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserVoiceMessageEndToEndInstrumentedTest {
    @Test
    fun uploadsDeviceGeneratedWaveForAuthenticatedBrowserPlayer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString(ARG_ENDPOINT).orEmpty()
        val token = arguments.getString(ARG_DEVICE_TOKEN).orEmpty()
        val deviceId = arguments.getString(ARG_DEVICE_ID).orEmpty()
        val messageId = arguments.getString(ARG_MESSAGE_ID).orEmpty()
        val createdAtEpochMillis = arguments.getString(ARG_CREATED_AT)?.toLongOrNull()
        assumeTrue(
            "browser voice E2E requires endpoint, device token, device ID, message ID and creation time",
            listOf(endpoint, token, deviceId, messageId).all(String::isNotBlank) &&
                createdAtEpochMillis != null && createdAtEpochMillis > 0,
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val mediaDirectory = File(context.filesDir, "media/voice").apply {
            check(mkdirs() || isDirectory)
        }
        val file = File(mediaDirectory, "$messageId.wav")
        writeTestWave(file)
        val asset = MediaAsset(
            assetId = messageId,
            kind = MediaKind.VOICE,
            filePath = file.absolutePath,
            mimeType = "audio/wav",
            byteSize = file.length(),
            sha256 = MediaIntegrity.sha256(file),
            width = 0,
            height = 0,
            durationMillis = DURATION_MILLIS,
            createdAtEpochMillis = requireNotNull(createdAtEpochMillis),
            deviceId = deviceId,
            relatedEventId = "browser-voice-e2e",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            voiceSenderId = deviceId,
            voiceSenderRole = VoiceMessageSenderRole.DEVICE,
            voiceAllowedRoles = setOf(
                VoiceMessageRole.DISPATCHER,
                VoiceMessageRole.SUPERVISOR,
                VoiceMessageRole.ADMIN,
            ),
        )
        val store = MediaStore(HelmetDatabase.get(context))
        try {
            assertTrue(store.add(asset))
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = endpoint,
                    backendBearerToken = token,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                ),
            )
            val result = TestListenableWorkerBuilder<MediaUploadWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            val delivered = store.find(messageId)
            assertEquals(MediaTransferState.DELIVERED, delivered?.transferState)
            assertEquals(1, delivered?.attemptCount)
            assertEquals(asset.sha256, delivered?.sha256)
            assertEquals(asset.voiceSenderRole, delivered?.voiceSenderRole)
            assertEquals(asset.voiceAllowedRoles, delivered?.voiceAllowedRoles)
        } finally {
            configStore.save(originalConfig)
            file.delete()
        }
    }

    private fun writeTestWave(file: File) {
        val sampleCount = SAMPLE_RATE * DURATION_MILLIS.toInt() / 1_000
        val pcmByteCount = sampleCount * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(WAVE_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmByteCount)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
            putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmByteCount)
        }
        val pcm = ByteBuffer.allocate(pcmByteCount).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { index ->
            val sample = (AMPLITUDE * sin(2.0 * PI * TONE_HERTZ * index / SAMPLE_RATE)).toInt()
            pcm.putShort(sample.toShort())
        }
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(pcm.array())
            output.fd.sync()
        }
        check(file.isFile && file.length() == WAVE_HEADER_BYTES + pcmByteCount.toLong()) {
            "browser voice test wave was not created"
        }
    }

    companion object {
        private const val ARG_ENDPOINT = "backendEndpoint"
        private const val ARG_DEVICE_TOKEN = "deviceToken"
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_MESSAGE_ID = "messageId"
        private const val ARG_CREATED_AT = "createdAtEpochMillis"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val DURATION_MILLIS = 1_500L
        private const val TONE_HERTZ = 440.0
        private const val AMPLITUDE = 12_000.0
        private const val WAVE_HEADER_BYTES = 44
    }
}
