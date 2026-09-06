package com.example.helmet.service.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.helmet.alert.sync.AlertUploadException
import com.example.helmet.alert.sync.HttpAlertUploadClient
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class SafetyAlertWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return Result.success()
        val client = runCatching {
            HttpAlertUploadClient(config.backendBaseUrl, config.backendBearerToken)
        }.getOrElse { return Result.failure() }
        val database = HelmetDatabase.get(applicationContext)
        val wallClock = DeviceTimeAuthorityProvider.get(applicationContext)::nowEpochMillis
        val safetyStore = SafetyStore(database)
        val eventStore = EventStore(database, wallClock)
        val alerts = safetyStore.pendingAlerts(MAX_ALERTS_PER_RUN)
        if (alerts.isEmpty()) return Result.success()

        for (alert in alerts) {
            val attemptedAt = wallClock()
            safetyStore.markAttempt(alert.messageId, attemptedAt)
            try {
                val receipt = client.upload(alert)
                safetyStore.markDelivered(alert.messageId, wallClock())
                eventStore.record(
                    eventType = "SAFETY_ALERT_UPLOAD_COMPLETED",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "messageId" to receipt.messageId,
                            "alertId" to receipt.alertId,
                            "deduplicated" to receipt.deduplicated,
                        ),
                    ).toString(),
                )
                if (shouldAnnounceSafetyAlertUploaded(alert.alarmType, alert.active)) {
                    runCatching {
                        applicationContext.startService(
                            HelmetService.safetyAlertDeliveredIntent(applicationContext, alert.messageId),
                        )
                    }.onFailure { error ->
                        StructuredLogger.warn(
                            event = "safety_alert_delivery_prompt_deferred",
                            fields = mapOf(
                                "messageId" to alert.messageId,
                                "errorType" to error.javaClass.name,
                            ),
                        )
                    }
                }
            } catch (error: AlertUploadException) {
                if (error.retryable) {
                    val failure = persistedFailure(error, error.statusCode, REASON_RETRYABLE_FAILURE)
                    safetyStore.markFailed(alert.messageId, failure.asStorageText())
                    recordFailure(eventStore, alert.messageId, "SAFETY_ALERT_UPLOAD_RETRY_SCHEDULED", failure)
                    return Result.retry()
                }
                if (error.statusCode in PERMANENT_DATA_ERROR_CODES) {
                    val failure = persistedFailure(error, error.statusCode, REASON_PERMANENT_DATA_REJECTION)
                    safetyStore.markRejected(alert.messageId, failure.asStorageText())
                    recordFailure(eventStore, alert.messageId, "SAFETY_ALERT_UPLOAD_REJECTED", failure)
                    continue
                }
                val failure = persistedFailure(error, error.statusCode, REASON_CONFIGURATION_FAILURE)
                safetyStore.markFailed(alert.messageId, failure.asStorageText())
                recordFailure(eventStore, alert.messageId, "SAFETY_ALERT_UPLOAD_CONFIGURATION_FAILED", failure)
                return Result.failure()
            } catch (error: Throwable) {
                val failure = persistedFailure(error, null, REASON_UNEXPECTED_FAILURE)
                safetyStore.markFailed(alert.messageId, failure.asStorageText())
                recordFailure(eventStore, alert.messageId, "SAFETY_ALERT_UPLOAD_RETRY_SCHEDULED", failure)
                return Result.retry()
            }
        }
        if (safetyStore.pendingAlertCount() > 0) enqueue(applicationContext, continuation = true)
        return Result.success()
    }

    private suspend fun recordFailure(
        eventStore: EventStore,
        messageId: String,
        eventType: String,
        failure: PersistedFailure,
    ) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "messageId" to messageId,
                    *failure.toEventFields().toList().toTypedArray(),
                ),
            ).toString(),
        )
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-safety-alert-upload"
        private const val MAX_ALERTS_PER_RUN = 100
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
            val request = OneTimeWorkRequestBuilder<SafetyAlertWorker>()
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
