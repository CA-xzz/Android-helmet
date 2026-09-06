package com.example.helmet.feature.camera

import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageAssetFactoryTest {
    @Test
    fun createsUploadableDeviceVoiceMetadataFromFinalFile() {
        val file = File.createTempFile("voice-message-", ".m4a")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))

            val asset = VoiceMessageAssetFactory.create(
                entry = CaptureJournalEntry(
                    assetId = "voice-1",
                    kind = MediaKind.VOICE,
                    state = CaptureJournalState.PREPARED,
                    partialRelativePath = "voice/voice-1.m4a.partial",
                    finalRelativePath = "voice/voice-1.m4a",
                    mimeType = "audio/mp4",
                    expectedByteSize = file.length(),
                    expectedSha256 = MediaFileIntegrityInspector.inspect(file).sha256,
                    width = 0,
                    height = 0,
                    durationMillis = 1_000,
                    createdAtEpochMillis = 2_000,
                    deviceId = "device-1",
                    personId = "person-1",
                    relatedEventId = "event-1",
                    latitude = null,
                    longitude = null,
                    horizontalAccuracyMeters = null,
                    locationFixType = "NO_FIX",
                    voiceSenderId = "device-1",
                    voiceSenderRole = VoiceMessageSenderRole.DEVICE,
                    voiceAllowedRoles = VoiceMessageRole.entries.toSet(),
                    voiceCallId = "call-1",
                ),
                file = file,
            )

            assertEquals(MediaKind.VOICE, asset.kind)
            assertEquals(MediaTransferState.PENDING, asset.transferState)
            assertEquals("audio/mp4", asset.mimeType)
            assertEquals(VoiceMessageSenderRole.DEVICE, asset.voiceSenderRole)
            assertEquals(VoiceMessageRole.entries.toSet(), asset.voiceAllowedRoles)
            assertEquals("device-1", asset.voiceSenderId)
            assertEquals("call-1", asset.voiceCallId)
            assertEquals("NO_FIX", asset.locationFixType)
            assertEquals(4L, asset.byteSize)
            assertTrue(asset.sha256.matches(Regex("[0-9a-f]{64}")))
        } finally {
            file.delete()
        }
    }
}
