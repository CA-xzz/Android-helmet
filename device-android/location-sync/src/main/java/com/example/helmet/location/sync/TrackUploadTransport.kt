package com.example.helmet.location.sync

import com.example.helmet.core.model.TrackPoint

data class TrackUploadReceipt(
    val acceptedMessageIds: Set<String>,
    val duplicateMessageIds: Set<String>,
)

class TrackUploadException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

fun interface TrackUploadTransport {
    suspend fun upload(points: List<TrackPoint>): TrackUploadReceipt
}
