package com.example.helmet.feature.camera

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.LocationFix
import java.io.File

data class MediaSize(val width: Int, val height: Int) {
    init {
        require(width > 0)
        require(height > 0)
    }

    val pixels: Long = width.toLong() * height.toLong()
}

data class CameraCapabilities(
    val cameraCount: Int,
    val selectedCameraId: String?,
    val lensFacing: String?,
    val maximumJpegSize: MediaSize?,
    val selectedVideoSize: MediaSize?,
    val supportsThirteenMegapixelPhoto: Boolean,
    val supports1080pVideo: Boolean,
    val hasCameraPermission: Boolean,
    val hasAudioPermission: Boolean,
)

interface MediaCaptureController {
    val isRecording: Boolean

    fun inspect(): CameraCapabilities
    suspend fun capturePhoto(relatedEventId: String? = null, locationFix: LocationFix? = null): MediaAsset
    suspend fun startRecording(relatedEventId: String? = null, locationFix: LocationFix? = null): String
    suspend fun stopRecording(): MediaAsset
    fun close()
}

class CameraOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class MediaLocationMetadata(
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val fixType: String,
)

object MediaLocationAssociation {
    fun from(fix: LocationFix?): MediaLocationMetadata {
        val usable = fix?.takeIf { it.hasPosition && !it.isMock }
        return MediaLocationMetadata(
            latitude = usable?.latitude,
            longitude = usable?.longitude,
            horizontalAccuracyMeters = usable?.horizontalAccuracyMeters,
            fixType = usable?.quality?.name ?: "NO_FIX",
        )
    }
}

object CameraSelection {
    fun largest(sizes: Collection<MediaSize>): MediaSize? = sizes.maxByOrNull(MediaSize::pixels)

    fun video1080pOrClosest(sizes: Collection<MediaSize>): MediaSize? {
        if (sizes.isEmpty()) return null
        sizes.firstOrNull { it.width == FULL_HD_WIDTH && it.height == FULL_HD_HEIGHT }?.let { return it }
        return sizes
            .filter { it.width <= FULL_HD_WIDTH && it.height <= FULL_HD_HEIGHT }
            .maxByOrNull(MediaSize::pixels)
            ?: sizes.minByOrNull { size ->
                kotlin.math.abs(size.width - FULL_HD_WIDTH) + kotlin.math.abs(size.height - FULL_HD_HEIGHT)
            }
    }

    const val FULL_HD_WIDTH = 1_920
    const val FULL_HD_HEIGHT = 1_080
    const val THIRTEEN_MEGAPIXELS = 13_000_000L
}

object MediaFileRecovery {
    fun deleteIncompleteFiles(mediaRoot: File): Int {
        if (!mediaRoot.isDirectory) return 0
        var deleted = 0
        mediaRoot.walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(PARTIAL_SUFFIX) }
            .forEach { file -> if (file.delete()) deleted += 1 }
        return deleted
    }

    private const val PARTIAL_SUFFIX = ".partial"
}
