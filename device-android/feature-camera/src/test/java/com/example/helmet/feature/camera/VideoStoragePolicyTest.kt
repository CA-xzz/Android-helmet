package com.example.helmet.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoragePolicyTest {
    @Test
    fun tenMinuteCapacityIncludesBothBitratesMarginAndFreeSpaceReserve() {
        assertEquals(762_000_000L, VideoStoragePolicy.maximumRecordingBytes())
        assertEquals(
            762_000_000L + 256L * 1024 * 1024,
            VideoStoragePolicy.minimumStartAvailableBytes(),
        )
    }

    @Test
    fun runtimeGuardStopsBeforeTheReserveIsConsumed() {
        assertTrue(
            VideoStoragePolicy.hasRuntimeReserve(VideoStoragePolicy.RUNTIME_FREE_SPACE_RESERVE_BYTES),
        )
        assertFalse(
            VideoStoragePolicy.hasRuntimeReserve(VideoStoragePolicy.RUNTIME_FREE_SPACE_RESERVE_BYTES - 1),
        )
    }
}
