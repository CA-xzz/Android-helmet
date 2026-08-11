package com.example.helmet.data.local

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MediaStore(private val database: HelmetDatabase) {
    suspend fun add(asset: MediaAsset): Boolean {
        require(asset.byteSize >= 0)
        require(asset.sha256.matches(SHA_256_PATTERN))
        val rowId = database.mediaDao().insert(
            MediaEntity(
                assetId = asset.assetId,
                kind = asset.kind.name,
                filePath = asset.filePath,
                mimeType = asset.mimeType,
                byteSize = asset.byteSize,
                sha256 = asset.sha256,
                width = asset.width,
                height = asset.height,
                durationMillis = asset.durationMillis,
                createdAtEpochMillis = asset.createdAtEpochMillis,
                deviceId = asset.deviceId,
                relatedEventId = asset.relatedEventId,
                transferState = asset.transferState.name,
                attemptCount = asset.attemptCount,
                lastAttemptAtEpochMillis = null,
                deliveredAtEpochMillis = null,
                lastError = null,
                personId = asset.personId,
                latitude = asset.latitude,
                longitude = asset.longitude,
                horizontalAccuracyMeters = asset.horizontalAccuracyMeters,
                locationFixType = asset.locationFixType,
                voiceSenderId = asset.voiceSenderId,
                voiceSenderRole = asset.voiceSenderRole?.name,
                voiceAllowedRoles = asset.voiceAllowedRoles.map { it.name }.sorted().joinToString(","),
                voiceCallId = asset.voiceCallId,
            ),
        )
        return rowId != -1L
    }

    fun observeRecent(limit: Int = 50): Flow<List<MediaAsset>> =
        database.mediaDao().observeRecent(limit).map { rows -> rows.map(MediaEntity::toModel) }

    suspend fun pending(limit: Int = 20): List<MediaAsset> =
        database.mediaDao().pending(limit).map(MediaEntity::toModel)

    suspend fun pendingCount(): Int = database.mediaDao().pendingCount()

    suspend fun find(assetId: String): MediaAsset? = database.mediaDao().find(assetId)?.toModel()

    suspend fun markAttempt(assetId: String, attemptAt: Long): Boolean =
        database.mediaDao().markAttempt(assetId, attemptAt) == 1

    suspend fun markDelivered(assetId: String, deliveredAt: Long): Boolean =
        database.mediaDao().markDelivered(assetId, deliveredAt) == 1

    suspend fun markFailed(assetId: String, error: String): Boolean =
        database.mediaDao().markFailed(assetId, error.take(MAX_ERROR_LENGTH)) == 1

    suspend fun markRejected(assetId: String, error: String): Boolean =
        database.mediaDao().markRejected(assetId, error.take(MAX_ERROR_LENGTH)) == 1

    companion object {
        private val SHA_256_PATTERN = Regex("^[0-9a-f]{64}$")
        private const val MAX_ERROR_LENGTH = 1_024
    }
}
