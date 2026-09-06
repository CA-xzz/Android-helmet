package com.example.helmet.media.sync

import com.example.helmet.core.model.MediaAsset
import java.io.InputStream

data class MediaContentIntegrity(val byteSize: Long, val sha256: String)

interface MediaContentSource {
    fun inspect(asset: MediaAsset): MediaContentIntegrity
    fun open(asset: MediaAsset, offset: Long): InputStream
}

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
