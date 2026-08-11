package com.example.helmet.alert.sync

import com.example.helmet.core.model.SafetyAlertRecord

data class AlertUploadReceipt(
    val messageId: String,
    val alertId: String,
    val deduplicated: Boolean,
)

class AlertUploadException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

fun interface AlertUploadTransport {
    suspend fun upload(alert: SafetyAlertRecord): AlertUploadReceipt
}
