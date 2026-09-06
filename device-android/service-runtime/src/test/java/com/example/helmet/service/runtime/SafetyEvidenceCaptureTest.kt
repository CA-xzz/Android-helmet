package com.example.helmet.service.runtime

import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.feature.camera.CameraCapabilities
import com.example.helmet.feature.camera.MediaCaptureController
import com.example.helmet.feature.camera.MediaCaptureEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEvidenceCaptureTest {
    @Test
    fun realMotionAlarmsRequestVideoEvidence() {
        listOf("FALL", "IMPACT", "VIOLENT_SHAKE", "INACTIVITY").forEach { alarmType ->
            assertTrue(shouldCaptureSafetyEvidenceVideo(alarmType, true, false, false))
        }
        assertFalse(shouldCaptureSafetyEvidenceVideo("NEAR_ELECTRIC", true, false, false))
        assertFalse(shouldCaptureSafetyEvidenceVideo("FALL", false, false, false))
        assertFalse(shouldCaptureSafetyEvidenceVideo("FALL", true, true, false))
        assertFalse(shouldCaptureSafetyEvidenceVideo("FALL", true, false, true))
    }

    @Test
    fun recordsTenSecondVideoWithStableAlarmIdentity() = runBlocking {
        val controller = FakeMediaController()
        var waited = 0L

        val result = recordSafetyEvidenceVideo(
            controller = controller,
            relatedEventId = "alert-message-1",
            locationFix = null,
            waitForDuration = { waited = it },
        )

        assertEquals(SAFETY_EVIDENCE_VIDEO_DURATION_MILLIS, waited)
        assertEquals("alert-message-1", controller.relatedEventId)
        assertEquals(SafetyEvidenceVideoResult.Captured(controller.asset, false), result)
        assertEquals(1, controller.startCount)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun finalizesShortenedClipWhenTheWaitPathFails() = runBlocking {
        val controller = FakeMediaController()

        val result = recordSafetyEvidenceVideo(
            controller = controller,
            relatedEventId = "alert-message-2",
            locationFix = null,
            waitForDuration = { error("timer failed") },
        )

        assertEquals(SafetyEvidenceVideoResult.Captured(controller.asset, true), result)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun reportsUnavailableWithoutInterruptingAnExistingRecording() = runBlocking {
        val controller = FakeMediaController(recording = true)

        val result = recordSafetyEvidenceVideo(controller, "alert-message-3", null)

        assertEquals(SafetyEvidenceVideoResult.Unavailable("VIDEO_RECORDING_ACTIVE"), result)
        assertEquals(0, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun reportsCameraStartFailureWithoutClaimingVideoCapture() = runBlocking {
        val controller = FakeMediaController(startFailure = IllegalStateException("no camera"))

        val result = recordSafetyEvidenceVideo(controller, "alert-message-4", null)

        assertEquals(
            SafetyEvidenceVideoResult.Failed(IllegalStateException::class.java.name, recordingStarted = false),
            result,
        )
        assertEquals(1, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun cancellationLeavesStartedRecordingForControllerShutdownRecovery() = runBlocking {
        val controller = FakeMediaController()

        val failure = runCatching {
            recordSafetyEvidenceVideo(
                controller = controller,
                relatedEventId = "alert-message-5",
                locationFix = null,
                waitForDuration = { throw CancellationException("service stopped") },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(controller.isRecording)
        assertEquals(0, controller.stopCount)
        assertEquals("alert-message-5", controller.relatedEventId)
    }

    private class FakeMediaController(
        recording: Boolean = false,
        private val startFailure: Throwable? = null,
    ) : MediaCaptureController {
        override var isRecording: Boolean = recording
        override val events: Flow<MediaCaptureEvent> = emptyFlow()
        var startCount = 0
        var stopCount = 0
        var relatedEventId: String? = null
        val asset = MediaAsset(
            assetId = "evidence-video",
            kind = MediaKind.VIDEO,
            filePath = "/tmp/evidence-video.mp4",
            mimeType = "video/mp4",
            byteSize = 1,
            sha256 = "0".repeat(64),
            width = 1_920,
            height = 1_080,
            durationMillis = SAFETY_EVIDENCE_VIDEO_DURATION_MILLIS,
            createdAtEpochMillis = 1,
            deviceId = "device-1",
            relatedEventId = "alert-message-1",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
        )

        override fun inspect() = CameraCapabilities(0, null, null, null, null, false, false, false, false)

        override suspend fun capturePhoto(relatedEventId: String?, locationFix: LocationFix?): MediaAsset =
            error("not used")

        override suspend fun startRecording(relatedEventId: String?, locationFix: LocationFix?): String {
            startCount += 1
            startFailure?.let { throw it }
            this.relatedEventId = relatedEventId
            isRecording = true
            return asset.assetId
        }

        override suspend fun stopRecording(): MediaAsset {
            stopCount += 1
            isRecording = false
            return asset
        }

        override fun close() = Unit
    }
}
