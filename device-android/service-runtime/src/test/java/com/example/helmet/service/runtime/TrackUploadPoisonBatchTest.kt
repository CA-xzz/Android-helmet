package com.example.helmet.service.runtime

import com.example.helmet.location.sync.TrackUploadException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackUploadPoisonBatchTest {
    @Test
    fun onePermanentPoisonIsIsolatedWithoutRejectingTheOther199Points() = runBlocking {
        val ids = (1..200).map { "point-$it" }
        val poison = "point-117"
        val delivered = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        val summary = uploadWithPermanentFailureIsolation(
            items = ids,
            upload = { batch ->
                if (poison in batch) {
                    throw TrackUploadException("conflict", retryable = false, statusCode = 409)
                }
                batch.toSet()
            },
            isPermanentDataFailure = { error ->
                error is TrackUploadException && !error.retryable && error.statusCode == 409
            },
            markDelivered = { batch, receipt ->
                assertEquals(batch.toSet(), receipt)
                delivered += batch
            },
            markRejected = { id, _ -> rejected += id },
        )

        assertEquals(ids.filterNot { it == poison }, delivered)
        assertEquals(listOf(poison), rejected)
        assertEquals(199, summary.deliveredCount)
        assertEquals(1, summary.rejectedCount)
        assertTrue(summary.uploadAttempts > 1)
    }

    @Test
    fun retryableFailureDuringIsolationEscapesWithoutRejectingUnresolvedPoints() = runBlocking {
        val rejected = mutableListOf<String>()
        var calls = 0

        assertThrows(TrackUploadException::class.java) {
            runBlocking {
                uploadWithPermanentFailureIsolation(
                    items = listOf("good", "poison"),
                    upload = { batch ->
                        calls += 1
                        if (batch.size > 1) {
                            throw TrackUploadException("conflict", retryable = false, statusCode = 409)
                        }
                        throw TrackUploadException("offline", retryable = true, statusCode = 503)
                    },
                    isPermanentDataFailure = { error ->
                        error is TrackUploadException && !error.retryable && error.statusCode == 409
                    },
                    markDelivered = { _, _ -> },
                    markRejected = { id, _ -> rejected += id },
                )
            }
        }

        assertEquals(2, calls)
        assertEquals(emptyList<String>(), rejected)
    }
}
