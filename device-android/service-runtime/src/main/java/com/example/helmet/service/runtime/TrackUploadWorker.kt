package com.example.helmet.service.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.TrackPoint
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.location.sync.HttpTrackUploadClient
import com.example.helmet.location.sync.TrackUploadReceipt
import com.example.helmet.location.sync.TrackUploadException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal data class TrackBatchIsolationSummary(
    val deliveredCount: Int,
    val rejectedCount: Int,
    val uploadAttempts: Int,
)

internal suspend fun <T, R> uploadWithPermanentFailureIsolation(
    items: List<T>,
    upload: suspend (List<T>) -> R,
    isPermanentDataFailure: (Throwable) -> Boolean,
    markDelivered: suspend (List<T>, R) -> Unit,
    markRejected: suspend (T, Throwable) -> Unit,
): TrackBatchIsolationSummary {
    require(items.isNotEmpty())
    var deliveredCount = 0
    var rejectedCount = 0
    var uploadAttempts = 0

    suspend fun visit(batch: List<T>) {
        uploadAttempts += 1
        val receipt = try {
            upload(batch)
        } catch (error: Throwable) {
            if (!isPermanentDataFailure(error)) throw error
            if (batch.size == 1) {
                markRejected(batch.single(), error)
                rejectedCount += 1
            } else {
                val midpoint = batch.size / 2
                visit(batch.subList(0, midpoint))
                visit(batch.subList(midpoint, batch.size))
            }
            return
        }
        markDelivered(batch, receipt)
        deliveredCount += batch.size
    }

    visit(items)
    return TrackBatchIsolationSummary(deliveredCount, rejectedCount, uploadAttempts)
}

class TrackUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return Result.success()
        val client = runCatching {
            HttpTrackUploadClient(config.backendBaseUrl, config.backendBearerToken)
        }.getOrElse { return Result.failure() }
        val database = HelmetDatabase.get(applicationContext)
        val wallClock = DeviceTimeAuthorityProvider.get(applicationContext)::nowEpochMillis
        val trackStore = TrackStore(database, wallClock)
        val eventStore = EventStore(database, wallClock)
        val points = trackStore.pending(MAX_POINTS_PER_RUN)
        if (points.isEmpty()) return Result.success()
        val attemptedAt = wallClock()
        points.forEach { point -> trackStore.markAttempt(point.messageId, attemptedAt) }
        return try {
            var acceptedCount = 0
            var duplicateCount = 0
            var lastRejection: TrackUploadException? = null
            val summary = uploadWithPermanentFailureIsolation(
                items = points,
                upload = client::upload,
                isPermanentDataFailure = { failure ->
                    failure is TrackUploadException &&
                        !failure.retryable &&
                        failure.statusCode in PERMANENT_DATA_ERROR_CODES
                },
                markDelivered = { batch, receipt: TrackUploadReceipt ->
                    val deliveredAt = wallClock()
                    batch.forEach { point -> trackStore.markDelivered(point.messageId, deliveredAt) }
                    acceptedCount += receipt.acceptedMessageIds.size
                    duplicateCount += receipt.duplicateMessageIds.size
                },
                markRejected = { point, failure ->
                    val uploadFailure = failure as TrackUploadException
                    lastRejection = uploadFailure
                    val persisted = persistedFailure(
                        uploadFailure,
                        uploadFailure.statusCode,
                        REASON_PERMANENT_DATA_REJECTION,
                    )
                    trackStore.markRejected(point.messageId, persisted.asStorageText())
                },
            )
            eventStore.record(
                eventType = if (summary.rejectedCount == 0) {
                    "TRACK_UPLOAD_COMPLETED"
                } else {
                    "TRACK_UPLOAD_PARTIALLY_REJECTED"
                },
                severity = if (summary.rejectedCount == 0) EventSeverity.INFO else EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "firstSequence" to points.first().sequence,
                        "lastSequence" to points.last().sequence,
                        "pointCount" to points.size,
                        "acceptedCount" to acceptedCount,
                        "duplicateCount" to duplicateCount,
                        "rejectedCount" to summary.rejectedCount,
                        "uploadAttempts" to summary.uploadAttempts,
                        "rejectionStatus" to lastRejection?.statusCode,
                        "rejectionErrorType" to lastRejection?.javaClass?.name,
                    ),
                ).toString(),
            )
            if (trackStore.pendingCount() > 0) enqueue(applicationContext, continuation = true)
            Result.success()
        } catch (error: TrackUploadException) {
            if (error.retryable) {
                val failure = persistedFailure(error, error.statusCode, REASON_RETRYABLE_FAILURE)
                points.forEach { point -> trackStore.markFailed(point.messageId, failure.asStorageText()) }
                recordFailure(eventStore, points, "TRACK_UPLOAD_RETRY_SCHEDULED", failure)
                Result.retry()
            } else {
                val failure = persistedFailure(error, error.statusCode, REASON_CONFIGURATION_FAILURE)
                points.forEach { point -> trackStore.markFailed(point.messageId, failure.asStorageText()) }
                recordFailure(eventStore, points, "TRACK_UPLOAD_CONFIGURATION_FAILED", failure)
                Result.failure()
            }
        } catch (error: Throwable) {
            val failure = persistedFailure(error, null, REASON_UNEXPECTED_FAILURE)
            points.forEach { point -> trackStore.markFailed(point.messageId, failure.asStorageText()) }
            recordFailure(eventStore, points, "TRACK_UPLOAD_RETRY_SCHEDULED", failure)
            Result.retry()
        }
    }

    private suspend fun recordFailure(
        eventStore: EventStore,
        points: List<TrackPoint>,
        eventType: String,
        failure: PersistedFailure,
    ) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "firstSequence" to points.first().sequence,
                    "lastSequence" to points.last().sequence,
                    "pointCount" to points.size,
                    *failure.toEventFields().toList().toTypedArray(),
                ),
            ).toString(),
        )
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-track-upload"
        private const val MAX_POINTS_PER_RUN = 200
        private const val REASON_RETRYABLE_FAILURE = "RETRYABLE_TRANSPORT_FAILURE"
        private const val REASON_PERMANENT_DATA_REJECTION = "PERMANENT_DATA_REJECTION"
        private const val REASON_CONFIGURATION_FAILURE = "TRANSPORT_CONFIGURATION_FAILURE"
        private const val REASON_UNEXPECTED_FAILURE = "UNEXPECTED_TRANSPORT_FAILURE"
        private val PERMANENT_DATA_ERROR_CODES = setOf(400, 409, 413, 422)
        private val reconciledWorkName = AtomicReference<String?>()

        fun enqueue(context: Context, continuation: Boolean = false) {
            val backendUrl = RuntimeConfigStore(context).load().backendBaseUrl
            val route = backendWorkRoute(LEGACY_UNIQUE_WORK, backendUrl)
            val workManager = WorkManager.getInstance(context)
            if (reconciledWorkName.getAndSet(route.activeName) != route.activeName) {
                workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK)
                workManager.cancelUniqueWork(route.staleName)
            }
            val request = OneTimeWorkRequestBuilder<TrackUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(route.requiredNetworkType)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(
                route.activeName,
                workPolicyForContinuation(continuation),
                request,
            )
        }
    }
}
