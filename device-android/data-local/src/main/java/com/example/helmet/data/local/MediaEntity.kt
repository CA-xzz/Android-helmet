package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole

@Entity(tableName = "media_assets")
data class MediaEntity(
    @PrimaryKey val assetId: String,
    val kind: String,
    val filePath: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val createdAtEpochMillis: Long,
    val deviceId: String,
    val relatedEventId: String?,
    val transferState: String,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
    val lastError: String?,
    val personId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val locationFixType: String,
    val voiceSenderId: String?,
    val voiceSenderRole: String?,
    val voiceAllowedRoles: String,
    val voiceCallId: String?,
) {
    fun toModel(): MediaAsset = MediaAsset(
        assetId = assetId,
        kind = MediaKind.valueOf(kind),
        filePath = filePath,
        mimeType = mimeType,
        byteSize = byteSize,
        sha256 = sha256,
        width = width,
        height = height,
        durationMillis = durationMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        deviceId = deviceId,
        relatedEventId = relatedEventId,
        transferState = MediaTransferState.valueOf(transferState),
        attemptCount = attemptCount,
        personId = personId,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        locationFixType = locationFixType,
        voiceSenderId = voiceSenderId,
        voiceSenderRole = voiceSenderRole?.let(VoiceMessageSenderRole::valueOf),
        voiceAllowedRoles = voiceAllowedRoles.takeIf(String::isNotBlank)
            ?.split(',')
            ?.map(VoiceMessageRole::valueOf)
            ?.toSet()
            .orEmpty(),
        voiceCallId = voiceCallId,
    )
}
