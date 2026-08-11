package com.example.helmet.media.sync

import com.example.helmet.core.model.MediaAsset

data class MediaUploadReceipt(
    val mediaId: String,
    val archiveId: String,
    val contentSha256: String,
    val bytesUploaded: Long,
    val deduplicated: Boolean,
)

interface MediaUploadTransport {
    suspend fun upload(asset: MediaAsset): MediaUploadReceipt
}

class MediaUploadException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
