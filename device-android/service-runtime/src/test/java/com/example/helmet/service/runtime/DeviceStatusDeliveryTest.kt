package com.example.helmet.service.runtime

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusDeliveryTest {
    @Test
    fun highFrequencyReplacementsAreCoalescedWithoutReplacingCoveredWork() {
        val coverage = DeviceStatusWorkCoverage()
        var persisted = 0
        var enqueued = 0

        repeat(1_000) {
            if (coverage.onReplacement { persisted += 1 } != null) enqueued += 1
        }

        assertEquals(1_000, persisted)
        assertEquals(1, enqueued)
    }

    @Test
    fun completedWorkerCannotReleaseAClaimCreatedInItsTailWindow() {
        val coverage = DeviceStatusWorkCoverage()
        val oldClaim = requireNotNull(coverage.onReplacement { })
        assertNull(coverage.takeLatestOrRelease<String>(oldClaim) { null })

        val newClaim = requireNotNull(coverage.onReplacement { })
        coverage.release(oldClaim)

        assertNull(coverage.onReplacement { })
        coverage.release(newClaim)
        assertTrue(coverage.onReplacement { } != null)
    }

    @Test
    fun forcedReplacementInvalidatesThePreviousWorkerClaim() {
        val coverage = DeviceStatusWorkCoverage()
        val oldClaim = requireNotNull(coverage.onReplacement { })
        val replacementClaim = coverage.forceReplacement()

        coverage.release(oldClaim)
        assertNull(coverage.onReplacement { })

        coverage.release(replacementClaim)
        assertTrue(coverage.onReplacement { } != null)
    }

    @Test
    fun updateDuringUploadIsDeliveredBySameRunAndOnlyLatestValueIsRetained() = runBlocking {
        val coverage = DeviceStatusWorkCoverage()
        var latest: String? = "status-1"
        val delivered = mutableListOf<String>()
        assertTrue(coverage.onReplacement { latest = "status-1" } != null)
        val workerClaimToken = coverage.workerStarted()

        val result = drainLatestDeviceStatuses(
            maxDeliveriesPerRun = 4,
            takeLatestOrRelease = { coverage.takeLatestOrRelease(workerClaimToken) { latest } },
            deliver = { status ->
                delivered += status
                if (status == "status-1") {
                    repeat(100) { index ->
                        assertNull(
                            coverage.onReplacement {
                                latest = "status-${index + 2}"
                            },
                        )
                    }
                }
                LatestDeviceStatusDeliveryOutcome.DELIVERED
            },
            clearIfCurrent = { status ->
                if (latest == status) latest = null
            },
        )

        assertEquals(LatestDeviceStatusDrainResult.SUCCESS, result)
        assertEquals(listOf("status-1", "status-101"), delivered)
        assertNull(latest)
        assertTrue(coverage.onReplacement { latest = "status-after-completion" } != null)
    }

    @Test
    fun boundedHealthyDrainRequestsImmediateContinuationInsteadOfBackoff() = runBlocking {
        val coverage = DeviceStatusWorkCoverage()
        var next = 1
        var latest: Int? = next
        coverage.onReplacement { }
        val workerClaimToken = coverage.workerStarted()

        val result = drainLatestDeviceStatuses(
            maxDeliveriesPerRun = 2,
            takeLatestOrRelease = { coverage.takeLatestOrRelease(workerClaimToken) { latest } },
            deliver = {
                next += 1
                coverage.onReplacement { latest = next }
                LatestDeviceStatusDeliveryOutcome.DELIVERED
            },
            clearIfCurrent = { status ->
                if (latest == status) latest = null
            },
        )

        assertEquals(LatestDeviceStatusDrainResult.CONTINUE, result)
        assertEquals(3, latest)
        assertNull(coverage.onReplacement { latest = 4 })

        val continuationResult = drainLatestDeviceStatuses(
            maxDeliveriesPerRun = 2,
            takeLatestOrRelease = { coverage.takeLatestOrRelease(workerClaimToken) { latest } },
            deliver = { LatestDeviceStatusDeliveryOutcome.DELIVERED },
            clearIfCurrent = { status ->
                if (latest == status) latest = null
            },
        )
        assertEquals(LatestDeviceStatusDrainResult.SUCCESS, continuationResult)
        assertEquals(null, latest)
    }

    @Test
    fun retryKeepsSnapshotAndFreshProcessCanSchedulePersistedLatestValue() = runBlocking {
        val coverage = DeviceStatusWorkCoverage()
        var latest: String? = "persisted"
        coverage.onReplacement { }
        val workerClaimToken = coverage.workerStarted()

        val result = drainLatestDeviceStatuses(
            maxDeliveriesPerRun = 4,
            takeLatestOrRelease = { coverage.takeLatestOrRelease(workerClaimToken) { latest } },
            deliver = { LatestDeviceStatusDeliveryOutcome.RETRYABLE_FAILURE },
            clearIfCurrent = { latest = null },
        )

        assertEquals(LatestDeviceStatusDrainResult.RETRY, result)
        assertEquals("persisted", latest)
        coverage.keepForRetry(workerClaimToken)
        assertNull(coverage.onReplacement { latest = "newer" })

        val afterProcessRestart = DeviceStatusWorkCoverage()
        assertTrue(afterProcessRestart.onReplacement { latest = "newest-after-restart" } != null)
        assertEquals("newest-after-restart", latest)
    }

    @Test
    fun corruptedSnapshotIsRejectedBeforeUpload() {
        val validJson = JSONObject()
            .put("messageId", "status-valid")
            .toString()

        assertEquals(
            DeviceStatusOutbox.StoredDeviceStatus("status-valid", validJson, 7),
            decodeDeviceStatusSnapshot("status-valid", validJson, 7L),
        )
        assertNull(decodeDeviceStatusSnapshot("status-valid", "{", 7L))
        assertNull(decodeDeviceStatusSnapshot("status-valid", validJson, "wrong-type"))
        assertNull(decodeDeviceStatusSnapshot("status-other", validJson, 7L))
    }

    @Test
    fun timestampSelectionDoesNotOverflowAtPersistentCounterLimit() {
        assertEquals(
            123L,
            selectNextDeviceStatusTime(
                candidate = 123L,
                previous = Long.MAX_VALUE,
                previousTimeMarker = 9L,
                currentTimeMarker = 9L,
            ),
        )
    }

    @Test
    fun persistentSequenceRecoversFromCorruptionButFailsClosedAtExhaustion() {
        assertEquals(8L, nextDeviceStatusSequence(7L))
        assertEquals(8L, nextDeviceStatusSequence(7))
        assertEquals(1L, nextDeviceStatusSequence("wrong-type"))
        assertEquals(1L, nextDeviceStatusSequence(-1L))
        assertEquals(Long.MAX_VALUE, nextDeviceStatusSequence(Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) {
            nextDeviceStatusSequence(Long.MAX_VALUE)
        }

        assertEquals(1L, nextDeviceStatusGeneration("wrong-type"))
        assertEquals(1L, nextDeviceStatusGeneration(-1L))
        assertEquals(Long.MAX_VALUE, nextDeviceStatusGeneration(Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) {
            nextDeviceStatusGeneration(Long.MAX_VALUE)
        }
    }
}
