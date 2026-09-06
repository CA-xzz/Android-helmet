package com.example.helmet.service.runtime

import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.feature.camera.MediaCaptureController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val SAFETY_EVIDENCE_VIDEO_DURATION_MILLIS = 10_000L

internal sealed interface SafetyEvidenceVideoResult {
    data class Captured(
        val asset: MediaAsset,
        val shortened: Boolean,
    ) : SafetyEvidenceVideoResult

    data class Unavailable(val reason: String) : SafetyEvidenceVideoResult

    data class Failed(
        val errorType: String,
        val recordingStarted: Boolean,
    ) : SafetyEvidenceVideoResult
}

internal fun shouldCaptureSafetyEvidenceVideo(
    alarmType: String,
    active: Boolean,
    simulated: Boolean,
    recoveryReplay: Boolean,
): Boolean = active && !simulated && !recoveryReplay &&
    alarmType in setOf("FALL", "IMPACT", "VIOLENT_SHAKE", "INACTIVITY")

internal suspend fun recordSafetyEvidenceVideo(
    controller: MediaCaptureController,
    relatedEventId: String,
    locationFix: LocationFix?,
    durationMillis: Long = SAFETY_EVIDENCE_VIDEO_DURATION_MILLIS,
    waitForDuration: suspend (Long) -> Unit = { delay(it) },
): SafetyEvidenceVideoResult {
    require(relatedEventId.isNotBlank())
    require(durationMillis > 0)
    if (controller.isRecording) {
        return SafetyEvidenceVideoResult.Unavailable("VIDEO_RECORDING_ACTIVE")
    }

    var recordingStarted = false
    return try {
        controller.startRecording(relatedEventId, locationFix)
        recordingStarted = true
        waitForDuration(durationMillis)
        SafetyEvidenceVideoResult.Captured(controller.stopRecording(), shortened = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (recordingStarted && controller.isRecording) {
            runCatching { controller.stopRecording() }.getOrNull()?.let { asset ->
                return SafetyEvidenceVideoResult.Captured(asset, shortened = true)
            }
        }
        SafetyEvidenceVideoResult.Failed(
            errorType = error.javaClass.name.take(MAX_SAFETY_EVIDENCE_ERROR_TYPE_LENGTH),
            recordingStarted = recordingStarted,
        )
    }
}

private const val MAX_SAFETY_EVIDENCE_ERROR_TYPE_LENGTH = 256
