package com.example.helmet.media.sync

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMediaUploadClientTest {
    @Test
    fun endpointRequiresHttpsExceptForLoopbackTests() {
        assertEquals(
            "https://media.example.test",
            HttpMediaUploadClient.validateAndNormalizeBaseUrl("https://media.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpMediaUploadClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpMediaUploadClient.validateAndNormalizeBaseUrl("http://media.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMediaUploadClient.validateAndNormalizeBaseUrl("https://user:secret@media.example.test")
        }
    }

    @Test
    fun integrityHashIsStable() {
        val file = File.createTempFile("media-integrity", ".bin")
        try {
            file.writeBytes("helmet-media".toByteArray())
            assertEquals(
                "fd3e362bb31deadb5e4995f72a2b9a6f98c2b305dad2c3180229d8b116b78843",
                MediaIntegrity.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun voiceMetadataCarriesAuthorizationWithoutVisualFields() {
        val metadata = HttpMediaUploadClient.metadataJson(
            MediaAsset(
                assetId = "voice-1",
                kind = MediaKind.VOICE,
                filePath = "/private/voice.wav",
                mimeType = "audio/wav",
                byteSize = 32_044,
                sha256 = "a".repeat(64),
                width = 0,
                height = 0,
                durationMillis = 1_000,
                createdAtEpochMillis = 100,
                deviceId = "device-1",
                relatedEventId = "event-1",
                transferState = MediaTransferState.PENDING,
                attemptCount = 0,
                voiceSenderId = "device-1",
                voiceSenderRole = VoiceMessageSenderRole.DEVICE,
                voiceAllowedRoles = setOf(VoiceMessageRole.DISPATCHER, VoiceMessageRole.ADMIN),
                voiceCallId = "call-1",
            ),
        )

        assertEquals("VOICE", metadata.getString("kind"))
        assertEquals("device-1", metadata.getString("senderId"))
        assertEquals("DEVICE", metadata.getString("senderRole"))
        assertEquals("call-1", metadata.getString("callId"))
        assertEquals(listOf("ADMIN", "DISPATCHER"), metadata.getJSONArray("allowedRoles").let { roles ->
            List(roles.length(), roles::getString)
        })
        assertFalse(metadata.has("width"))
        assertFalse(metadata.has("height"))
        assertFalse(metadata.has("location"))
    }

    @Test
    fun visualMetadataRejectsInvalidDurationAndLocationCombinations() {
        val photo = visualAsset()
        HttpMediaUploadClient.validateMetadata(photo)

        val videoWithoutDuration = photo.copy(
            assetId = "video-1",
            kind = MediaKind.VIDEO,
            filePath = "/private/video.mp4",
            mimeType = "video/mp4",
        )
        val noFixWithCoordinates = photo.copy(latitude = 31.2, longitude = 121.4)

        assertTrue(assertThrows(MediaUploadException::class.java) {
            HttpMediaUploadClient.validateMetadata(videoWithoutDuration)
        }.retryable.not())
        assertTrue(assertThrows(MediaUploadException::class.java) {
            HttpMediaUploadClient.validateMetadata(noFixWithCoordinates)
        }.retryable.not())
    }

    @Test
    fun chunkAcknowledgementMustConsumeExactlyTheBytesAlreadyRead() {
        assertEquals(65_536L, HttpMediaUploadClient.requireExactNextOffset("photo-1", 0, 65_536, 65_536))
        val partial = assertThrows(MediaUploadException::class.java) {
            HttpMediaUploadClient.requireExactNextOffset("photo-1", 0, 65_536, 32_768)
        }
        assertFalse(partial.retryable)
    }

    private fun visualAsset() = MediaAsset(
        assetId = "photo-1",
        kind = MediaKind.PHOTO,
        filePath = "/private/photo.jpg",
        mimeType = "image/jpeg",
        byteSize = 100,
        sha256 = "b".repeat(64),
        width = 4_160,
        height = 3_120,
        durationMillis = null,
        createdAtEpochMillis = 100,
        deviceId = "device-1",
        relatedEventId = "event-1",
        transferState = MediaTransferState.PENDING,
        attemptCount = 0,
        locationFixType = "NO_FIX",
    )
}
