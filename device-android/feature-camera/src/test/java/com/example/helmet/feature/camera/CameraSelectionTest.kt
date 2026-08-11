package com.example.helmet.feature.camera

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSelectionTest {
    @Test
    fun selectsLargestPhotoAndExactFullHdVideo() {
        val sizes = listOf(MediaSize(640, 480), MediaSize(1_920, 1_080), MediaSize(4_160, 3_120))

        assertEquals(MediaSize(4_160, 3_120), CameraSelection.largest(sizes))
        assertEquals(MediaSize(1_920, 1_080), CameraSelection.video1080pOrClosest(sizes))
    }

    @Test
    fun videoFallsBackToLargestSizeWithinFullHd() {
        val sizes = listOf(MediaSize(640, 480), MediaSize(1_280, 720), MediaSize(3_840, 2_160))

        assertEquals(MediaSize(1_280, 720), CameraSelection.video1080pOrClosest(sizes))
    }

    @Test
    fun emptyCapabilitiesReturnNull() {
        assertNull(CameraSelection.largest(emptyList()))
        assertNull(CameraSelection.video1080pOrClosest(emptyList()))
    }

    @Test
    fun recoveryDeletesOnlyIncompleteMediaFiles() {
        val root = createTempDirectory("helmet-media-recovery").toFile()
        try {
            val nested = File(root, "video").apply { mkdirs() }
            val incomplete = File(nested, "asset.mp4.partial").apply { writeBytes(byteArrayOf(1)) }
            val complete = File(nested, "asset.mp4").apply { writeBytes(byteArrayOf(2)) }

            assertEquals(1, MediaFileRecovery.deleteIncompleteFiles(root))
            assertEquals(false, incomplete.exists())
            assertTrue(complete.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mediaLocationUsesOnlyRealPositionFixes() {
        val real = LocationFix(
            fixId = "fix-1",
            deviceId = "device-1",
            occurredAtEpochMillis = 100,
            elapsedRealtimeNanos = 100,
            source = LocationSource.ANDROID_GNSS,
            quality = FixQuality.STANDARD,
            latitude = 31.2,
            longitude = 121.4,
            horizontalAccuracyMeters = 2.5f,
        )
        assertEquals(
            MediaLocationMetadata(31.2, 121.4, 2.5f, "STANDARD"),
            MediaLocationAssociation.from(real),
        )
        assertEquals(
            MediaLocationMetadata(null, null, null, "NO_FIX"),
            MediaLocationAssociation.from(real.copy(isMock = true)),
        )
        assertEquals(
            MediaLocationMetadata(null, null, null, "NO_FIX"),
            MediaLocationAssociation.from(null),
        )
    }
}
