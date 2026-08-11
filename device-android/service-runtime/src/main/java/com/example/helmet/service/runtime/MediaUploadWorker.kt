package com.example.helmet.service.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.media.sync.HttpMediaUploadClient
import com.example.helmet.media.sync.MediaUploadException
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class MediaUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) {
            StructuredLogger.info(
                event = "media_upload_skipped",
                fields = mapOf("transportConfigured" to false),
            )
            return Result.success()
        }
        val client = runCatching {
            HttpMediaUploadClient(config.backendBaseUrl, config.backendBearerToken)
        }.getOrElse { error ->
            StructuredLogger.error(
                event = "media_upload_configuration_invalid",
                error = error,
            )
            return Result.failure()
        }
        val database = HelmetDatabase.get(applicationContext)
        val wallClock = DeviceTimeAuthorityProvider.get(applicationContext)::nowEpochMillis
        val mediaStore = MediaStore(database)
        val eventStore = EventStore(database, wallClock)
        val assets = mediaStore.pending(MAX_ASSETS_PER_RUN)
        var shouldRetry = false
        for (asset in assets) {
            val pathFailure = validatePrivateMediaPath(asset)
            if (pathFailure != null) {
                mediaStore.markRejected(asset.assetId, pathFailure)
                recordResult(eventStore, asset, "MEDIA_UPLOAD_REJECTED", pathFailure)
                continue
            }
            mediaStore.markAttempt(asset.assetId, wallClock())
            try {
                val receipt = client.upload(asset)
                mediaStore.markDelivered(asset.assetId, wallClock())
                eventStore.record(
                    eventType = "MEDIA_UPLOAD_COMPLETED",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "assetId" to asset.assetId,
                            "archiveId" to receipt.archiveId,
                            "sha256" to receipt.contentSha256,
                            "bytesUploaded" to receipt.bytesUploaded,
                            "deduplicated" to receipt.deduplicated,
                        ),
                    ).toString(),
                )
            } catch (error: MediaUploadException) {
                if (error.retryable) {
                    mediaStore.markFailed(asset.assetId, error.toString())
                    shouldRetry = true
                } else {
                    mediaStore.markRejected(asset.assetId, error.toString())
                }
                recordResult(
                    eventStore,
                    asset,
                    if (error.retryable) "MEDIA_UPLOAD_RETRY_SCHEDULED" else "MEDIA_UPLOAD_REJECTED",
                    error.toString(),
                )
                if (error.retryable) break
            } catch (error: Throwable) {
                mediaStore.markFailed(asset.assetId, error.toString())
                recordResult(eventStore, asset, "MEDIA_UPLOAD_RETRY_SCHEDULED", error.toString())
                shouldRetry = true
                break
            }
        }
        if (shouldRetry) return Result.retry()
        if (mediaStore.pendingCount() > 0) enqueue(applicationContext, continuation = true)
        return Result.success()
    }

    private fun validatePrivateMediaPath(asset: MediaAsset): String? {
        val root = File(applicationContext.filesDir, "media").canonicalFile
        val file = runCatching { File(asset.filePath).canonicalFile }.getOrElse {
            return "media path cannot be resolved"
        }
        val insideRoot = file.path.startsWith(root.path + File.separator)
        return when {
            !insideRoot -> "media path is outside the private media directory"
            !file.isFile -> "media file is missing"
            else -> null
        }
    }

    private suspend fun recordResult(
        eventStore: EventStore,
        asset: MediaAsset,
        eventType: String,
        error: String,
    ) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "assetId" to asset.assetId,
                    "sha256" to asset.sha256,
                    "error" to error.take(MAX_ERROR_LENGTH),
                ),
            ).toString(),
        )
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-media-upload"
        private const val MAX_ASSETS_PER_RUN = 100
        private const val MAX_ERROR_LENGTH = 1_024
        private val reconciledWorkName = AtomicReference<String?>()

        fun enqueue(context: Context, continuation: Boolean = false) {
            val backendUrl = RuntimeConfigStore(context).load().backendBaseUrl
            val route = backendWorkRoute(LEGACY_UNIQUE_WORK, backendUrl)
            val workManager = WorkManager.getInstance(context)
            if (reconciledWorkName.getAndSet(route.activeName) != route.activeName) {
                workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK)
                workManager.cancelUniqueWork(route.staleName)
            }
            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
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
