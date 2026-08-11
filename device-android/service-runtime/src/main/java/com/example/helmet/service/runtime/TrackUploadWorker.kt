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
import com.example.helmet.location.sync.TrackUploadException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

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
            val receipt = client.upload(points)
            val deliveredAt = wallClock()
            points.forEach { point -> trackStore.markDelivered(point.messageId, deliveredAt) }
            eventStore.record(
                eventType = "TRACK_UPLOAD_COMPLETED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "firstSequence" to points.first().sequence,
                        "lastSequence" to points.last().sequence,
                        "pointCount" to points.size,
                        "acceptedCount" to receipt.acceptedMessageIds.size,
                        "duplicateCount" to receipt.duplicateMessageIds.size,
                    ),
                ).toString(),
            )
            if (trackStore.pendingCount() > 0) enqueue(applicationContext, continuation = true)
            Result.success()
        } catch (error: TrackUploadException) {
            if (error.retryable) {
                points.forEach { point -> trackStore.markFailed(point.messageId, error.toString()) }
                recordFailure(eventStore, points, "TRACK_UPLOAD_RETRY_SCHEDULED", error)
                Result.retry()
            } else if (error.statusCode in PERMANENT_DATA_ERROR_CODES) {
                points.forEach { point -> trackStore.markRejected(point.messageId, error.toString()) }
                recordFailure(eventStore, points, "TRACK_UPLOAD_REJECTED", error)
                Result.failure()
            } else {
                points.forEach { point -> trackStore.markFailed(point.messageId, error.toString()) }
                recordFailure(eventStore, points, "TRACK_UPLOAD_CONFIGURATION_FAILED", error)
                Result.failure()
            }
        } catch (error: Throwable) {
            points.forEach { point -> trackStore.markFailed(point.messageId, error.toString()) }
            recordFailure(eventStore, points, "TRACK_UPLOAD_RETRY_SCHEDULED", error)
            Result.retry()
        }
    }

    private suspend fun recordFailure(
        eventStore: EventStore,
        points: List<TrackPoint>,
        eventType: String,
        error: Throwable,
    ) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "firstSequence" to points.first().sequence,
                    "lastSequence" to points.last().sequence,
                    "pointCount" to points.size,
                    "error" to error.toString().take(MAX_ERROR_LENGTH),
                ),
            ).toString(),
        )
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-track-upload"
        private const val MAX_POINTS_PER_RUN = 200
        private const val MAX_ERROR_LENGTH = 1_024
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
