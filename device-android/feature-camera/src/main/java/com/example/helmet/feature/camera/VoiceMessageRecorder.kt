package com.example.helmet.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.EncryptedMediaFileStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface VoiceMessageCaptureController {
    val isRecording: Boolean
    val events: Flow<MediaCaptureEvent>

    suspend fun start(
        relatedEventId: String,
        locationFix: LocationFix? = null,
        callId: String? = null,
    ): String

    suspend fun stop(): MediaAsset
    fun close()
}

class AndroidVoiceMessageRecorder(
    context: Context,
    private val mediaStore: MediaStore,
    private val deviceId: String,
    private val personId: String? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : VoiceMessageCaptureController {
    private data class ActiveRecording(
        val assetId: String,
        val partialFile: File,
        val finalFile: File,
        val startedAtMonotonicMillis: Long,
        val recorder: MediaRecorder,
        val journalEntry: CaptureJournalEntry,
        val terminationSignal: CompletableDeferred<TerminationRequest>,
        val terminationGate: CaptureTerminationGate = CaptureTerminationGate(),
        val terminalResult: CompletableDeferred<Result<MediaAsset>> = CompletableDeferred(),
    )

    private data class TerminationRequest(
        val reason: MediaCaptureTerminationReason,
        val cause: Throwable? = null,
    )

    private val applicationContext = context.applicationContext
    private val mediaRoot = File(applicationContext.filesDir, "media/voice").apply { mkdirs() }
    private val captureRoot = requireNotNull(mediaRoot.parentFile)
    private val encryptedMedia = EncryptedMediaFileStorage(applicationContext)
    private val journalStore = CaptureJournalStore(captureRoot, encryptedMedia)
    private val operationMutex = Mutex()
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEvents = MutableSharedFlow<MediaCaptureEvent>(replay = 1, extraBufferCapacity = 8)

    override val events: Flow<MediaCaptureEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var activeRecording: ActiveRecording? = null

    override val isRecording: Boolean
        get() = activeRecording != null

    override suspend fun start(
        relatedEventId: String,
        locationFix: LocationFix?,
        callId: String?,
    ): String = operationMutex.withLock {
        require(relatedEventId.isNotBlank())
        check(activeRecording == null) { "voice message recording is already active" }
        checkPermission()
        ensureAvailableBytes()
        mediaStore.findByRelatedEventAndKind(deviceId, relatedEventId, MediaKind.VOICE)
            ?.let { return@withLock it.assetId }
        val assetId = MediaAssetIdentity.create(deviceId, MediaKind.VOICE, relatedEventId, idFactory)
        mediaStore.find(assetId)?.let { return@withLock it.assetId }
        val partialFile = File(mediaRoot, "$assetId.m4a.partial")
        val finalFile = File(mediaRoot, "$assetId.m4a")
        check(!journalStore.exists(assetId) && !partialFile.exists() && !finalFile.exists()) {
            "voice capture awaits startup media recovery"
        }
        val startedAtEpochMillis = wallClock()
        val location = MediaLocationAssociation.from(locationFix)
        val journal = CaptureJournalEntry(
            assetId = assetId,
            kind = MediaKind.VOICE,
            state = CaptureJournalState.CAPTURING,
            partialRelativePath = "voice/$assetId.m4a.partial",
            finalRelativePath = "voice/$assetId.m4a",
            mimeType = "audio/mp4",
            expectedByteSize = null,
            expectedSha256 = null,
            width = 0,
            height = 0,
            durationMillis = null,
            createdAtEpochMillis = startedAtEpochMillis,
            deviceId = deviceId,
            personId = personId,
            relatedEventId = relatedEventId,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.horizontalAccuracyMeters,
            locationFixType = location.fixType,
            voiceSenderId = deviceId,
            voiceSenderRole = VoiceMessageSenderRole.DEVICE,
            voiceAllowedRoles = VoiceMessageRole.entries.toSet(),
            voiceCallId = callId,
        )
        journalStore.write(journal)
        var recorder: MediaRecorder? = null
        val terminationSignal = CompletableDeferred<TerminationRequest>()
        try {
            recorder = MediaRecorder(applicationContext).apply {
                setOnErrorListener { _, what, extra ->
                    terminationSignal.complete(
                        TerminationRequest(
                            MediaCaptureTerminationReason.RECORDER_ERROR,
                            CameraOperationException("voice recorder error $what/$extra"),
                        ),
                    )
                }
                setOnInfoListener { _, what, _ ->
                    val reason = when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ->
                            MediaCaptureTerminationReason.DURATION_LIMIT
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                            MediaCaptureTerminationReason.FILE_SIZE_LIMIT
                        else -> null
                    }
                    if (reason != null) terminationSignal.complete(TerminationRequest(reason))
                }
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(partialFile.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setAudioChannels(AUDIO_CHANNELS)
                setMaxDuration(MAX_DURATION_MILLIS.toInt())
                setMaxFileSize(MAX_FILE_BYTES)
                prepare()
                start()
            }
            activeRecording = ActiveRecording(
                assetId = assetId,
                partialFile = partialFile,
                finalFile = finalFile,
                startedAtMonotonicMillis = monotonicClock(),
                recorder = recorder,
                journalEntry = journal,
                terminationSignal = terminationSignal,
            )
            observeTermination(requireNotNull(activeRecording))
            assetId
        } catch (error: Throwable) {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            partialFile.delete()
            if (!finalFile.exists()) journalStore.clear(journal)
            throw CameraOperationException("voice message recording start failed", error)
        }
    }

    override suspend fun stop(): MediaAsset {
        val recording = activeRecording
            ?: throw CameraOperationException("voice message recording is not active")
        return terminate(recording, TerminationRequest(MediaCaptureTerminationReason.USER)).getOrThrow()
    }

    override fun close() {
        val recording = activeRecording
        if (recording == null) {
            recorderScope.cancel()
            return
        }
        recorderScope.launch {
            terminate(recording, TerminationRequest(MediaCaptureTerminationReason.CLOSED))
            recorderScope.cancel()
        }
    }

    private fun observeTermination(recording: ActiveRecording) {
        recorderScope.launch {
            select<Unit> {
                recording.terminationSignal.onAwait { request -> terminate(recording, request) }
                recording.terminalResult.onAwait { }
            }
        }
    }

    private suspend fun terminate(
        recording: ActiveRecording,
        request: TerminationRequest,
    ): Result<MediaAsset> {
        if (!recording.terminationGate.tryClaim()) return recording.terminalResult.await()
        operationMutex.withLock {
            if (activeRecording === recording) activeRecording = null
        }
        val result = runCatching { finalize(recording, request) }
        recording.terminalResult.complete(result)
        result.fold(
            onSuccess = { asset ->
                mutableEvents.emit(
                    MediaCaptureEvent.Finalized(asset.assetId, MediaKind.VOICE, asset, request.reason),
                )
            },
            onFailure = { error ->
                mutableEvents.emit(
                    MediaCaptureEvent.Failed(
                        recording.assetId,
                        MediaKind.VOICE,
                        request.reason,
                        error.javaClass.name,
                    ),
                )
            },
        )
        return result
    }

    private suspend fun finalize(
        recording: ActiveRecording,
        request: TerminationRequest,
    ): MediaAsset {
        val stopFailure = runCatching { recording.recorder.stop() }.exceptionOrNull()
        release(recording.recorder)
        val acceptsRecorderStoppedFile = request.reason in setOf(
            MediaCaptureTerminationReason.DURATION_LIMIT,
            MediaCaptureTerminationReason.FILE_SIZE_LIMIT,
            MediaCaptureTerminationReason.CLOSED,
        )
        if (request.reason == MediaCaptureTerminationReason.RECORDER_ERROR ||
            stopFailure != null && !acceptsRecorderStoppedFile
        ) {
            recording.partialFile.delete()
            journalStore.clear(recording.journalEntry)
            throw CameraOperationException("voice recorder did not stop cleanly", request.cause ?: stopFailure)
        }
        if (!recording.partialFile.isFile || recording.partialFile.length() <= 0) {
            journalStore.clear(recording.journalEntry)
            throw CameraOperationException("voice recorder produced no complete file", request.cause ?: stopFailure)
        }
        runCatching { VoiceMessageAudioInspector.inspect(recording.partialFile) }
            .getOrElse { error ->
                recording.partialFile.delete()
                journalStore.clear(recording.journalEntry)
                throw CameraOperationException("voice recorder produced no audio samples", error)
            }
        val durationMillis = (monotonicClock() - recording.startedAtMonotonicMillis)
            .coerceIn(1, MAX_DURATION_MILLIS)
        syncFile(recording.partialFile)
        val integrity = MediaFileIntegrityInspector.inspect(recording.partialFile)
        val prepared = recording.journalEntry.copy(
            state = CaptureJournalState.PREPARED,
            durationMillis = durationMillis,
            expectedByteSize = integrity.byteSize,
            expectedSha256 = integrity.sha256,
        )
        journalStore.write(prepared)
        if (!recording.finalFile.exists()) {
            check(journalStore.promotePrepared(prepared).canonicalPath == recording.finalFile.canonicalPath)
        }
        check(recording.finalFile.isFile && recording.finalFile.length() > 0) {
            "voice message final file is missing"
        }
        encryptedMedia.encryptInPlace(
            recording.finalFile,
            requireNotNull(prepared.expectedByteSize),
            requireNotNull(prepared.expectedSha256),
        )
        val asset = mediaStore.addIdempotently(
            VoiceMessageAssetFactory.create(prepared, recording.finalFile, integrity),
        )
        check(journalStore.clear(prepared)) { "failed to clear committed voice journal" }
        return asset
    }

    private fun checkPermission() {
        if (
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CameraOperationException("missing permission ${Manifest.permission.RECORD_AUDIO}")
        }
    }

    private fun ensureAvailableBytes() {
        val available = StatFs(mediaRoot.absolutePath).availableBytes
        if (available < MIN_AVAILABLE_BYTES) {
            throw CameraOperationException(
                "insufficient storage: $available bytes available, $MIN_AVAILABLE_BYTES required",
            )
        }
    }

    private fun release(recorder: MediaRecorder) {
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    companion object {
        const val MAX_DURATION_MILLIS = 60_000L
        private const val MIN_AVAILABLE_BYTES = 16L * 1024 * 1024
        private const val MAX_FILE_BYTES = 1024L * 1024
        private const val AUDIO_BIT_RATE = 64_000
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_CHANNELS = 1
    }
}

internal data class VoiceMessageAudioEvidence(
    val audioTrackMimeType: String,
    val encodedSampleCount: Int,
    val encodedByteCount: Long,
)

internal object VoiceMessageAudioInspector {
    fun inspect(file: File): VoiceMessageAudioEvidence {
        require(file.isFile && file.length() > 0) { "voice message file is empty" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("voice message has no audio track")
            val format = extractor.getTrackFormat(track)
            val mimeType = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val bufferSize = file.length().coerceIn(MIN_INSPECTION_BUFFER_BYTES, MAX_INSPECTION_BUFFER_BYTES)
                .toInt()
            val buffer = ByteBuffer.allocate(bufferSize)
            var sampleCount = 0
            var encodedByteCount = 0L
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                check(sampleSize > 0) { "voice message contains an empty audio sample" }
                sampleCount += 1
                encodedByteCount += sampleSize.toLong()
                if (!extractor.advance()) break
            }
            check(sampleCount > 0 && encodedByteCount > 0) { "voice message contains no audio samples" }
            return VoiceMessageAudioEvidence(mimeType, sampleCount, encodedByteCount)
        } finally {
            extractor.release()
        }
    }

    private const val MIN_INSPECTION_BUFFER_BYTES = 64L * 1024
    private const val MAX_INSPECTION_BUFFER_BYTES = 1024L * 1024
}

internal object VoiceMessageAssetFactory {
    fun create(
        entry: CaptureJournalEntry,
        file: File,
        integrity: MediaFileIntegrity = MediaFileIntegrityInspector.inspect(file),
    ): MediaAsset {
        require(entry.kind == MediaKind.VOICE)
        return entry.toMediaAsset(file, integrity)
    }
}
