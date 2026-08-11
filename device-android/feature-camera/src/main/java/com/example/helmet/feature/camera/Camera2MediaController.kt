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
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.LocationFix
import com.example.helmet.data.local.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
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
        val relatedEventId: String?,
        val partialFile: File,
        val finalFile: File,
        val width: Int,
        val height: Int,
        val startedAtEpochMillis: Long,
        val startedAtMonotonicMillis: Long,
        val locationFix: LocationFix?,
        val camera: CameraDevice,
        val session: CameraCaptureSession,
        val recorder: MediaRecorder,
    )

    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private val mediaRoot = File(applicationContext.filesDir, "media").apply { mkdirs() }
    private val cameraThread = HandlerThread("helmet-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val operationMutex = Mutex()

    init {
        MediaFileRecovery.deleteIncompleteFiles(mediaRoot)
    }

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
        val descriptor = requireDescriptor()
        checkPermission(Manifest.permission.CAMERA)
        check(activeRecording == null) { "video recording is active" }
        ensureAvailableBytes(MIN_PHOTO_AVAILABLE_BYTES)
        val assetId = idFactory()
        val files = mediaFiles(MediaKind.PHOTO, assetId, "jpg")
        val reader = ImageReader.newInstance(
            descriptor.jpegSize.width,
            descriptor.jpegSize.height,
            ImageFormat.JPEG,
            2,
        )
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val imageBytes = CompletableDeferred<ByteArray>()
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val buffer = image.planes.first().buffer
                    ByteArray(buffer.remaining()).also(buffer::get).let(imageBytes::complete)
                }
            }, cameraHandler)
            camera = openCamera(descriptor.cameraId)
            session = createSession(camera, listOf(reader.surface))
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, descriptor.sensorOrientation)
            }.build()
            session.capture(request, null, cameraHandler)
            val bytes = withTimeout(CAPTURE_TIMEOUT_MILLIS) { imageBytes.await() }
            writeDurably(files.first, bytes)
            moveComplete(files.first, files.second)
            persistAsset(
                assetId = assetId,
                kind = MediaKind.PHOTO,
                file = files.second,
                mimeType = "image/jpeg",
                width = descriptor.jpegSize.width,
                height = descriptor.jpegSize.height,
                durationMillis = null,
                relatedEventId = relatedEventId,
                locationFix = locationFix,
            )
        } catch (error: Throwable) {
            files.first.delete()
            files.second.delete()
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
        checkPermission(Manifest.permission.RECORD_AUDIO)
        check(activeRecording == null) { "video recording is already active" }
        ensureAvailableBytes(MIN_VIDEO_AVAILABLE_BYTES)
        val assetId = idFactory()
        val files = mediaFiles(MediaKind.VIDEO, assetId, "mp4")
        var recorder: MediaRecorder? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            recorder = MediaRecorder(applicationContext).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(files.first.absolutePath)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoSize(descriptor.videoSize.width, descriptor.videoSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setOrientationHint(descriptor.sensorOrientation)
                prepare()
            }
            camera = openCamera(descriptor.cameraId)
            session = createSession(camera, listOf(recorder.surface))
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorder.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            recorder.start()
            activeRecording = ActiveRecording(
                assetId = assetId,
                relatedEventId = relatedEventId,
                partialFile = files.first,
                finalFile = files.second,
                width = descriptor.videoSize.width,
                height = descriptor.videoSize.height,
                startedAtEpochMillis = wallClock(),
                startedAtMonotonicMillis = monotonicClock(),
                locationFix = locationFix,
                camera = camera,
                session = session,
                recorder = recorder,
            )
            assetId
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            files.first.delete()
            files.second.delete()
            throw CameraOperationException("video start failed", error)
        }
    }

    override suspend fun stopRecording(): MediaAsset = operationMutex.withLock {
        val recording = activeRecording ?: throw CameraOperationException("video recording is not active")
        activeRecording = null
        val durationMillis = (monotonicClock() - recording.startedAtMonotonicMillis).coerceAtLeast(0)
        try {
            runCatching { recording.session.stopRepeating() }
            recording.recorder.stop()
            releaseRecording(recording)
            syncFile(recording.partialFile)
            moveComplete(recording.partialFile, recording.finalFile)
            persistAsset(
                assetId = recording.assetId,
                kind = MediaKind.VIDEO,
                file = recording.finalFile,
                mimeType = "video/mp4",
                width = recording.width,
                height = recording.height,
                durationMillis = durationMillis,
                relatedEventId = recording.relatedEventId,
                createdAtEpochMillis = recording.startedAtEpochMillis,
                locationFix = recording.locationFix,
            )
        } catch (error: Throwable) {
            releaseRecording(recording)
            recording.partialFile.delete()
            recording.finalFile.delete()
            throw CameraOperationException("video stop failed", error)
        }
    }

    override fun close() {
        activeRecording?.let { recording ->
            activeRecording = null
            runCatching { recording.recorder.stop() }
            releaseRecording(recording)
            recording.partialFile.delete()
        }
        cameraThread.quitSafely()
    }

    private suspend fun persistAsset(
        assetId: String,
        kind: MediaKind,
        file: File,
        mimeType: String,
        width: Int,
        height: Int,
        durationMillis: Long?,
        relatedEventId: String?,
        createdAtEpochMillis: Long = wallClock(),
        locationFix: LocationFix? = null,
    ): MediaAsset {
        val location = MediaLocationAssociation.from(locationFix)
        val asset = MediaAsset(
            assetId = assetId,
            kind = kind,
            filePath = file.absolutePath,
            mimeType = mimeType,
            byteSize = file.length(),
            sha256 = sha256(file),
            width = width,
            height = height,
            durationMillis = durationMillis,
            createdAtEpochMillis = createdAtEpochMillis,
            deviceId = deviceId,
            relatedEventId = relatedEventId,
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            personId = personId,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.horizontalAccuracyMeters,
            locationFixType = location.fixType,
        )
        check(mediaStore.add(asset)) { "media asset ID already exists" }
        return asset
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
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (continuation.isActive) continuation.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(CameraOperationException("camera disconnected"))
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(CameraOperationException("camera open error $error"))
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

    private fun moveComplete(partial: File, complete: File) {
        check(partial.renameTo(complete)) { "failed to finalize ${complete.name}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
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
        private const val MIN_VIDEO_AVAILABLE_BYTES = 512L * 1024 * 1024
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_SAMPLE_RATE = 48_000
    }
}
