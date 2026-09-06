package com.example.helmet.service.runtime

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.feature.camera.MediaCaptureTerminationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapturePolicyTest {
    @Test
    fun asynchronousLimitsHaveDistinctServiceEventsButUserStopIsNotDuplicated() {
        assertEquals(
            "VIDEO_RECORDING_FILE_SIZE_LIMIT_REACHED",
            asyncMediaEventPolicy(
                MediaKind.VIDEO,
                MediaCaptureTerminationReason.FILE_SIZE_LIMIT,
                success = true,
            )?.eventType,
        )
        assertEquals(
            "VOICE_MESSAGE_RECORDING_LIMIT_REACHED",
            asyncMediaEventPolicy(
                MediaKind.VOICE,
                MediaCaptureTerminationReason.DURATION_LIMIT,
                success = true,
            )?.eventType,
        )
        assertEquals(
            EventSeverity.MEDIUM,
            asyncMediaEventPolicy(
                MediaKind.VIDEO,
                MediaCaptureTerminationReason.CAMERA_DISCONNECTED,
                success = true,
            )?.severity,
        )
        assertNull(
            asyncMediaEventPolicy(
                MediaKind.VIDEO,
                MediaCaptureTerminationReason.USER,
                success = true,
            ),
        )
    }

    @Test
    fun genericMediaEventExcludesPrivatePathIdentityAndExactCoordinates() {
        val fields = mediaEventPayloadFields(
            MediaAsset(
                assetId = "asset-1",
                kind = MediaKind.PHOTO,
                filePath = "/private/secret/photo.jpg",
                mimeType = "image/jpeg",
                byteSize = 4,
                sha256 = "a".repeat(64),
                width = 4_160,
                height = 3_120,
                durationMillis = null,
                createdAtEpochMillis = 1_000,
                deviceId = "device-private",
                relatedEventId = "event-1",
                transferState = MediaTransferState.PENDING,
                attemptCount = 0,
                personId = "person-private",
                latitude = 31.234567,
                longitude = 121.456789,
                horizontalAccuracyMeters = 1.5f,
                locationFixType = "RTK_FIXED",
            ),
        )

        assertEquals(
            setOf(
                "assetId",
                "kind",
                "byteSize",
                "sha256",
                "relatedEventId",
                "locationFixType",
                "transferState",
            ),
            fields.keys,
        )
        val serialized = fields.values.joinToString("|")
        assertFalse("/private/secret" in serialized)
        assertFalse("person-private" in serialized)
        assertFalse("31.234567" in serialized)
        assertFalse("121.456789" in serialized)
        assertFalse("device-private" in serialized)
    }

    @Test
    fun failurePayloadContainsTypesWithoutThrowableMessages() {
        val failure = IllegalStateException(
            "bearer secret-token",
            IllegalArgumentException("31.234567,121.456789"),
        )

        val fields = mediaFailurePayloadFields(failure)
        val serialized = fields.values.joinToString("|")

        assertEquals(setOf("errorType", "causeType"), fields.keys)
        assertTrue("java.lang.IllegalStateException" in serialized)
        assertTrue("java.lang.IllegalArgumentException" in serialized)
        assertFalse("secret-token" in serialized)
        assertFalse("31.234567" in serialized)
    }
}
