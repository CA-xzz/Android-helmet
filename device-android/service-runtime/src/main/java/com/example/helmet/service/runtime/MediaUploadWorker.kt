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
        val database = HelmetDatabase.get(applicationContext)
        val wallClock = DeviceTimeAuthorityProvider.get(applicationContext)::nowEpochMillis
        val mediaStore = MediaStore(database)
        val eventStore = EventStore(database, wallClock)
        reconcileRetention(mediaStore, wallClock, "before_upload")
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) {
            StructuredLogger.info(
                event = "media_upload_skipped",
                fields = mapOf("transportConfigured" to false),
            )
            return Result.success()
        }
        val client = runCatching {
            HttpMediaUploadClient(
                config.backendBaseUrl,
                config.backendBearerToken,
                contentSource = EncryptedMediaContentSource(applicationContext),
            )
        }.getOrElse { error ->
            StructuredLogger.error(
                event = "media_upload_configuration_invalid",
                error = error,
            )
            return Result.failure()
        }
        val assets = mediaStore.pending(MAX_ASSETS_PER_RUN)
        var shouldRetry = false
        for (asset in assets) {
            val pathFailure = validatePrivateMediaPath(asset)
            if (pathFailure != null) {
                mediaStore.markRejected(asset.assetId, pathFailure.asStorageText())
                recordResult(eventStore, asset, "MEDIA_UPLOAD_REJECTED", pathFailure)
                continue
            }
            if (!mediaStore.markAttempt(asset.assetId, wallClock())) continue
            try {
                val receipt = client.upload(asset)
                if (!mediaStore.markDelivered(asset.assetId, wallClock())) continue
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
                val failure = persistedFailure(
                    error,
                    error.statusCode,
                    if (error.retryable) REASON_RETRYABLE_FAILURE else REASON_PERMANENT_REJECTION,
                )
                if (error.retryable) {
                    mediaStore.markFailed(asset.assetId, failure.asStorageText())
                    shouldRetry = true
                } else {
                    mediaStore.markRejected(asset.assetId, failure.asStorageText())
                }
                recordResult(
                    eventStore,
                    asset,
                    if (error.retryable) "MEDIA_UPLOAD_RETRY_SCHEDULED" else "MEDIA_UPLOAD_REJECTED",
                    failure,
                )
                if (error.retryable) break
            } catch (error: Throwable) {
                val failure = persistedFailure(error, null, REASON_UNEXPECTED_FAILURE)
                mediaStore.markFailed(asset.assetId, failure.asStorageText())
                recordResult(eventStore, asset, "MEDIA_UPLOAD_RETRY_SCHEDULED", failure)
                shouldRetry = true
                break
            }
        }
        if (shouldRetry) return Result.retry()
        reconcileRetention(mediaStore, wallClock, "after_upload")
        if (mediaStore.pendingCount() > 0) enqueue(applicationContext, continuation = true)
        return Result.success()
    }

    private suspend fun reconcileRetention(
        mediaStore: MediaStore,
        wallClock: () -> Long,
        source: String,
    ) {
        runCatching { reconcileMediaStorage(applicationContext, mediaStore, wallClock) }
            .onFailure { error ->
                StructuredLogger.warn(
                    event = "media_retention_failed",
                    fields = mapOf("source" to source, "errorType" to error.javaClass.name),
                )
            }
    }

    private fun validatePrivateMediaPath(asset: MediaAsset): PersistedFailure? {
        val root = File(applicationContext.filesDir, "media").canonicalFile
        val file = runCatching { File(asset.filePath).canonicalFile }.getOrElse {
            return PersistedFailure(PATH_VALIDATION_ERROR_TYPE, null, REASON_PATH_UNRESOLVABLE)
        }
        val insideRoot = file.path.startsWith(root.path + File.separator)
        return when {
            !insideRoot -> PersistedFailure(PATH_VALIDATION_ERROR_TYPE, null, REASON_PATH_OUTSIDE_PRIVATE_ROOT)
            !file.isFile -> PersistedFailure(PATH_VALIDATION_ERROR_TYPE, null, REASON_FILE_MISSING)
            else -> null
        }
    }

    private suspend fun recordResult(
        eventStore: EventStore,
        asset: MediaAsset,
        eventType: String,
        failure: PersistedFailure,
    ) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "assetId" to asset.assetId,
                    "sha256" to asset.sha256,
                    *failure.toEventFields().toList().toTypedArray(),
                ),
            ).toString(),
        )
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-media-upload"
        private const val MAX_ASSETS_PER_RUN = 100
        private const val PATH_VALIDATION_ERROR_TYPE = "MediaPathValidation"
        private const val REASON_PATH_UNRESOLVABLE = "PATH_UNRESOLVABLE"
        private const val REASON_PATH_OUTSIDE_PRIVATE_ROOT = "PATH_OUTSIDE_PRIVATE_ROOT"
        private const val REASON_FILE_MISSING = "MEDIA_FILE_MISSING"
        private const val REASON_RETRYABLE_FAILURE = "RETRYABLE_TRANSPORT_FAILURE"
        private const val REASON_PERMANENT_REJECTION = "PERMANENT_TRANSPORT_REJECTION"
        private const val REASON_UNEXPECTED_FAILURE = "UNEXPECTED_TRANSPORT_FAILURE"
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
