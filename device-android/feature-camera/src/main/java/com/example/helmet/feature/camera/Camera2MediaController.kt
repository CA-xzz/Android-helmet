package com.example.helmet.feature.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.util.Size
import androidx.core.content.ContextCompat
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.LocationFix
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.EncryptedMediaFileStorage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine

class Camera2MediaController(
    context: Context,
    private val mediaStore: MediaStore,
    private val deviceId: String,
    private val personId: String? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : MediaCaptureController {
    private data class Descriptor(
        val cameraId: String,
        val lensFacing: String,
        val jpegSize: Size,
        val videoSize: Size,
        val sensorOrientation: Int,
    )

    private data class ActiveRecording(
        val assetId: String,
        val partialFile: File,
        val finalFile: File,
        val startedAtMonotonicMillis: Long,
        val camera: CameraDevice,
        val session: CameraCaptureSession,
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
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private val mediaRoot = File(applicationContext.filesDir, "media").apply { mkdirs() }
    private val cameraThread = HandlerThread("helmet-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val operationMutex = Mutex()
    private val encryptedMedia = EncryptedMediaFileStorage(applicationContext)
    private val journalStore = CaptureJournalStore(mediaRoot, encryptedMedia)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEvents = MutableSharedFlow<MediaCaptureEvent>(replay = 1, extraBufferCapacity = 8)

    override val events: Flow<MediaCaptureEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var activeRecording: ActiveRecording? = null

    override val isRecording: Boolean
        get() = activeRecording != null

    override fun inspect(): CameraCapabilities {
        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        val descriptor = runCatching { selectDescriptor(ids) }.getOrNull()
        val jpeg = descriptor?.jpegSize?.toMediaSize()
        val video = descriptor?.videoSize?.toMediaSize()
        return CameraCapabilities(
            cameraCount = ids.size,
            selectedCameraId = descriptor?.cameraId,
            lensFacing = descriptor?.lensFacing,
            maximumJpegSize = jpeg,
            selectedVideoSize = video,
            supportsThirteenMegapixelPhoto = (jpeg?.pixels ?: 0L) >= CameraSelection.THIRTEEN_MEGAPIXELS,
            supports1080pVideo = video?.let {
                it.width == CameraSelection.FULL_HD_WIDTH && it.height == CameraSelection.FULL_HD_HEIGHT
            } == true,
            hasCameraPermission = hasPermission(Manifest.permission.CAMERA),
            hasAudioPermission = hasPermission(Manifest.permission.RECORD_AUDIO),
        )
    }

    override suspend fun capturePhoto(relatedEventId: String?, locationFix: LocationFix?): MediaAsset = operationMutex.withLock {
        check(activeRecording == null) { "video recording is active" }
        relatedEventId?.takeIf(String::isNotBlank)?.let { eventId ->
            mediaStore.findByRelatedEventAndKind(deviceId, eventId, MediaKind.PHOTO)?.let { return@withLock it }
        }
        val assetId = MediaAssetIdentity.create(deviceId, MediaKind.PHOTO, relatedEventId, idFactory)
        mediaStore.find(assetId)?.let { return@withLock it }
        val descriptor = requireDescriptor()
        checkPermission(Manifest.permission.CAMERA)
        ensureAvailableBytes(MIN_PHOTO_AVAILABLE_BYTES)
        val files = mediaFiles(MediaKind.PHOTO, assetId, "jpg")
        check(!journalStore.exists(assetId) && !files.first.exists() && !files.second.exists()) {
            "photo capture awaits startup media recovery"
        }
        val location = MediaLocationAssociation.from(locationFix)
        var journal = CaptureJournalEntry(
            assetId = assetId,
            kind = MediaKind.PHOTO,
            state = CaptureJournalState.CAPTURING,
            partialRelativePath = relativeMediaPath(files.first),
            finalRelativePath = relativeMediaPath(files.second),
            mimeType = "image/jpeg",
            expectedByteSize = null,
            expectedSha256 = null,
            width = descriptor.jpegSize.width,
            height = descriptor.jpegSize.height,
            durationMillis = null,
            createdAtEpochMillis = wallClock(),
            deviceId = deviceId,
            personId = personId,
            relatedEventId = relatedEventId,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.horizontalAccuracyMeters,
            locationFixType = location.fixType,
        )
        journalStore.write(journal)
        val reader = ImageReader.newInstance(
            descriptor.jpegSize.width,
            descriptor.jpegSize.height,
            ImageFormat.JPEG,
            2,
        )
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var prepared = false
        try {
            val imageBytes = CompletableDeferred<ByteArray>()
            val cameraFailure = CompletableDeferred<Throwable>()
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val buffer = image.planes.first().buffer
                    ByteArray(buffer.remaining()).also(buffer::get).let(imageBytes::complete)
                }
            }, cameraHandler)
            camera = openCamera(descriptor.cameraId) { error -> cameraFailure.complete(error) }
            session = createSession(camera, listOf(reader.surface))
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, descriptor.sensorOrientation)
            }.build()
            session.capture(request, null, cameraHandler)
            val bytes = withTimeout(CAPTURE_TIMEOUT_MILLIS) {
                select {
                    imageBytes.onAwait { it }
                    cameraFailure.onAwait { throw it }
                }
            }
            check(bytes.isNotEmpty()) { "camera returned an empty JPEG" }
            writeDurably(files.first, bytes)
            val integrity = MediaFileIntegrityInspector.inspect(files.first)
            journal = journal.copy(
                state = CaptureJournalState.PREPARED,
                expectedByteSize = integrity.byteSize,
                expectedSha256 = integrity.sha256,
            )
            journalStore.write(journal)
            prepared = true
            check(journalStore.promotePrepared(journal).canonicalPath == files.second.canonicalPath)
            encryptedMedia.encryptInPlace(
                files.second,
                requireNotNull(journal.expectedByteSize),
                requireNotNull(journal.expectedSha256),
            )
            val asset = mediaStore.addIdempotently(
                journal.toMediaAsset(
                    files.second,
                    MediaFileIntegrity(
                        requireNotNull(journal.expectedByteSize),
                        requireNotNull(journal.expectedSha256),
                    ),
                ),
            )
            check(journalStore.clear(journal)) { "failed to clear committed photo journal" }
            asset
        } catch (error: Throwable) {
            if (!prepared && !files.second.exists()) {
                files.first.delete()
                journalStore.clear(journal)
            }
            throw CameraOperationException("photo capture failed", error)
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            reader.close()
        }
    }

    override suspend fun startRecording(relatedEventId: String?, locationFix: LocationFix?): String = operationMutex.withLock {
        val descriptor = requireDescriptor()
        checkPermission(Manifest.permission.CAMERA)
        check(activeRecording == null) { "video recording is already active" }
        ensureAvailableBytes(VideoStoragePolicy.minimumStartAvailableBytes())
        val includeAudio = videoRecordingIncludesAudio(
            hasMicrophoneFeature = applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_MICROPHONE,
            ),
            hasRecordAudioPermission = hasPermission(Manifest.permission.RECORD_AUDIO),
        )
        val assetId = MediaAssetIdentity.create(deviceId, MediaKind.VIDEO, relatedEventId, idFactory)
        check(mediaStore.find(assetId) == null) { "video capture is already finalized" }
        val files = mediaFiles(MediaKind.VIDEO, assetId, "mp4")
        check(!journalStore.exists(assetId) && !files.first.exists() && !files.second.exists()) {
            "video capture awaits startup media recovery"
        }
        val startedAtEpochMillis = wallClock()
        val location = MediaLocationAssociation.from(locationFix)
        val journal = CaptureJournalEntry(
            assetId = assetId,
            kind = MediaKind.VIDEO,
            state = CaptureJournalState.CAPTURING,
            partialRelativePath = relativeMediaPath(files.first),
            finalRelativePath = relativeMediaPath(files.second),
            mimeType = "video/mp4",
            expectedByteSize = null,
            expectedSha256 = null,
            width = descriptor.videoSize.width,
            height = descriptor.videoSize.height,
            durationMillis = null,
            createdAtEpochMillis = startedAtEpochMillis,
            deviceId = deviceId,
            personId = personId,
            relatedEventId = relatedEventId,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.horizontalAccuracyMeters,
            locationFixType = location.fixType,
        )
        journalStore.write(journal)
        var recorder: MediaRecorder? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val terminationSignal = CompletableDeferred<TerminationRequest>()
        try {
            recorder = MediaRecorder(applicationContext).apply {
                setOnErrorListener { _, what, extra ->
                    terminationSignal.complete(
                        TerminationRequest(
                            MediaCaptureTerminationReason.RECORDER_ERROR,
                            CameraOperationException("media recorder error $what/$extra"),
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
                if (includeAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(files.first.absolutePath)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoSize(descriptor.videoSize.width, descriptor.videoSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (includeAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(AUDIO_BIT_RATE)
                    setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                }
                setOrientationHint(descriptor.sensorOrientation)
                setMaxDuration(VideoStoragePolicy.MAX_RECORDING_DURATION_MILLIS.toInt())
                setMaxFileSize(VideoStoragePolicy.maximumRecordingBytes())
                prepare()
            }
            camera = openCamera(descriptor.cameraId) { error ->
                terminationSignal.complete(
                    TerminationRequest(
                        if (error.message?.contains("disconnected") == true) {
                            MediaCaptureTerminationReason.CAMERA_DISCONNECTED
                        } else {
                            MediaCaptureTerminationReason.CAMERA_ERROR
                        },
                        error,
                    ),
                )
            }
            session = createSession(camera, listOf(recorder.surface))
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorder.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            recorder.start()
            activeRecording = ActiveRecording(
                assetId = assetId,
                partialFile = files.first,
                finalFile = files.second,
                startedAtMonotonicMillis = monotonicClock(),
                camera = camera,
                session = session,
                recorder = recorder,
                journalEntry = journal,
                terminationSignal = terminationSignal,
            )
            observeRecordingTermination(requireNotNull(activeRecording))
            assetId
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            files.first.delete()
            if (!files.second.exists()) journalStore.clear(journal)
            throw CameraOperationException("video start failed", error)
        }
    }

    override suspend fun stopRecording(): MediaAsset {
        val recording = activeRecording ?: throw CameraOperationException("video recording is not active")
        return terminateRecording(recording, TerminationRequest(MediaCaptureTerminationReason.USER)).getOrThrow()
    }

    override fun close() {
        val recording = activeRecording
        if (recording == null) {
            cameraThread.quitSafely()
            controllerScope.cancel()
            return
        }
        controllerScope.launch {
            terminateRecording(recording, TerminationRequest(MediaCaptureTerminationReason.CLOSED))
            cameraThread.quitSafely()
            controllerScope.cancel()
        }
    }

    private fun requireDescriptor(): Descriptor =
        selectDescriptor(cameraManager.cameraIdList.toList())
            ?: throw CameraOperationException("no camera with JPEG and MediaRecorder outputs")

    private fun selectDescriptor(ids: List<String>): Descriptor? {
        val ranked = ids.sortedBy { id ->
            when (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> 0
                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                else -> 2
            }
        }
        return ranked.firstNotNullOfOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@firstNotNullOfOrNull null
            val jpeg = CameraSelection.largest(
                map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { size -> size.toMediaSize() },
            ) ?: return@firstNotNullOfOrNull null
            val video = CameraSelection.video1080pOrClosest(
                map.getOutputSizes(MediaRecorder::class.java).orEmpty().map { size -> size.toMediaSize() },
            ) ?: return@firstNotNullOfOrNull null
            Descriptor(
                cameraId = id,
                lensFacing = lensFacingName(characteristics.get(CameraCharacteristics.LENS_FACING)),
                jpegSize = Size(jpeg.width, jpeg.height),
                videoSize = Size(video.width, video.height),
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        cameraId: String,
        onRuntimeFailure: (Throwable) -> Unit,
    ): CameraDevice = suspendCancellableCoroutine { continuation ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (continuation.isActive) continuation.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                val failure = CameraOperationException("camera disconnected")
                if (continuation.isActive) {
                    continuation.resumeWithException(failure)
                } else {
                    onRuntimeFailure(failure)
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val failure = CameraOperationException("camera error $error")
                if (continuation.isActive) {
                    continuation.resumeWithException(failure)
                } else {
                    onRuntimeFailure(failure)
                }
            }
        }, cameraHandler)
    }

    private suspend fun createSession(
        camera: CameraDevice,
        surfaces: List<android.view.Surface>,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration),
                Executor { command -> cameraHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CameraOperationException("camera session configuration failed"),
                            )
                        }
                    }
                }
            ),
        )
    }

    private fun observeRecordingTermination(recording: ActiveRecording) {
        controllerScope.launch {
            select<Unit> {
                recording.terminationSignal.onAwait { request ->
                    terminateRecording(recording, request)
                }
                recording.terminalResult.onAwait { }
            }
        }
        controllerScope.launch {
            while (currentCoroutineContext().isActive && !recording.terminalResult.isCompleted) {
                delay(VideoStoragePolicy.STORAGE_CHECK_INTERVAL_MILLIS)
                if (!VideoStoragePolicy.hasRuntimeReserve(StatFs(mediaRoot.absolutePath).availableBytes)) {
                    recording.terminationSignal.complete(
                        TerminationRequest(MediaCaptureTerminationReason.STORAGE_RESERVE),
                    )
                }
            }
        }
    }

    private suspend fun terminateRecording(
        recording: ActiveRecording,
        request: TerminationRequest,
    ): Result<MediaAsset> {
        if (!recording.terminationGate.tryClaim()) return recording.terminalResult.await()
        operationMutex.withLock {
            if (activeRecording === recording) activeRecording = null
        }
        val result = runCatching { finalizeRecording(recording, request) }
        recording.terminalResult.complete(result)
        result.fold(
            onSuccess = { asset ->
                mutableEvents.emit(
                    MediaCaptureEvent.Finalized(asset.assetId, MediaKind.VIDEO, asset, request.reason),
                )
            },
            onFailure = { error ->
                mutableEvents.emit(
                    MediaCaptureEvent.Failed(
                        recording.assetId,
                        MediaKind.VIDEO,
                        request.reason,
                        error.javaClass.name,
                    ),
                )
            },
        )
        return result
    }

    private suspend fun finalizeRecording(
        recording: ActiveRecording,
        request: TerminationRequest,
    ): MediaAsset {
        runCatching { recording.session.stopRepeating() }
        val stopFailure = runCatching { recording.recorder.stop() }.exceptionOrNull()
        releaseRecording(recording)
        val acceptsRecorderStoppedFile = request.reason in setOf(
            MediaCaptureTerminationReason.DURATION_LIMIT,
            MediaCaptureTerminationReason.FILE_SIZE_LIMIT,
            MediaCaptureTerminationReason.STORAGE_RESERVE,
            MediaCaptureTerminationReason.CLOSED,
        )
        if (request.reason == MediaCaptureTerminationReason.RECORDER_ERROR ||
            stopFailure != null && !acceptsRecorderStoppedFile
        ) {
            recording.partialFile.delete()
            journalStore.clear(recording.journalEntry)
            throw CameraOperationException("video recorder did not stop cleanly", request.cause ?: stopFailure)
        }
        if (!recording.partialFile.isFile || recording.partialFile.length() <= 0) {
            journalStore.clear(recording.journalEntry)
            throw CameraOperationException("video recorder produced no complete file", request.cause ?: stopFailure)
        }
        val durationMillis = (monotonicClock() - recording.startedAtMonotonicMillis)
            .coerceIn(1, VideoStoragePolicy.MAX_RECORDING_DURATION_MILLIS)
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
            "video final file is missing"
        }
        encryptedMedia.encryptInPlace(
            recording.finalFile,
            requireNotNull(prepared.expectedByteSize),
            requireNotNull(prepared.expectedSha256),
        )
        val asset = mediaStore.addIdempotently(
            prepared.toMediaAsset(recording.finalFile, integrity),
        )
        check(journalStore.clear(prepared)) { "failed to clear committed video journal" }
        return asset
    }

    private fun releaseRecording(recording: ActiveRecording) {
        runCatching { recording.session.close() }
        runCatching { recording.camera.close() }
        runCatching { recording.recorder.reset() }
        runCatching { recording.recorder.release() }
    }

    private fun mediaFiles(kind: MediaKind, assetId: String, extension: String): Pair<File, File> {
        val directory = File(mediaRoot, kind.name.lowercase()).apply { mkdirs() }
        val finalFile = File(directory, "$assetId.$extension")
        return File(directory, "$assetId.$extension.partial") to finalFile
    }

    private fun relativeMediaPath(file: File): String =
        mediaRoot.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')

    private fun ensureAvailableBytes(required: Long) {
        val available = StatFs(mediaRoot.absolutePath).availableBytes
        if (available < required) {
            throw CameraOperationException("insufficient storage: $available bytes available, $required required")
        }
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    private fun checkPermission(permission: String) {
        if (!hasPermission(permission)) throw CameraOperationException("missing permission $permission")
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun lensFacingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun Size.toMediaSize() = MediaSize(width, height)

    companion object {
        private const val CAPTURE_TIMEOUT_MILLIS = 10_000L
        private const val MIN_PHOTO_AVAILABLE_BYTES = 32L * 1024 * 1024
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_SAMPLE_RATE = 48_000
    }
}

/** Capacity policy for the production recorder's configured video and audio bitrates. */
object VideoStoragePolicy {
    const val MAX_RECORDING_DURATION_MILLIS = 10L * 60 * 1_000
    const val STORAGE_CHECK_INTERVAL_MILLIS = 5_000L
    const val RUNTIME_FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024
    private const val VIDEO_BIT_RATE_BITS_PER_SECOND = 8_000_000L
    private const val AUDIO_BIT_RATE_BITS_PER_SECOND = 128_000L
    private const val CONTAINER_AND_ENCODER_MARGIN_PERCENT = 25L

    fun maximumRecordingBytes(
        durationMillis: Long = MAX_RECORDING_DURATION_MILLIS,
    ): Long {
        require(durationMillis > 0)
        val encodedBytes = Math.addExact(
            Math.multiplyExact(
                VIDEO_BIT_RATE_BITS_PER_SECOND + AUDIO_BIT_RATE_BITS_PER_SECOND,
                durationMillis,
            ),
            7_999L,
        ) / 8_000L
        val margin = Math.addExact(
            Math.multiplyExact(encodedBytes, CONTAINER_AND_ENCODER_MARGIN_PERCENT),
            99L,
        ) / 100L
        return Math.addExact(encodedBytes, margin)
    }

    fun minimumStartAvailableBytes(
        durationMillis: Long = MAX_RECORDING_DURATION_MILLIS,
    ): Long = Math.addExact(maximumRecordingBytes(durationMillis), RUNTIME_FREE_SPACE_RESERVE_BYTES)

    fun hasRuntimeReserve(availableBytes: Long): Boolean =
        availableBytes >= RUNTIME_FREE_SPACE_RESERVE_BYTES
}
