package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.core.model.TrackPoint

@Entity(
    tableName = "track_points",
    indices = [
        Index(value = ["deviceId", "sequence"], unique = true),
        Index(value = ["deliveryState", "sequence"]),
    ],
)
data class TrackPointEntity(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val sequence: Long,
    val fixId: String,
    val occurredAtEpochMillis: Long,
    val elapsedRealtimeNanos: Long?,
    val source: String,
    val quality: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Float?,
    val verticalAccuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val satellitesUsed: Int?,
    val satellitesVisible: Int?,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
    val correctionAgeSeconds: Double?,
    val correctionStationId: String?,
    val provider: String?,
    val isMock: Boolean,
    val deliveryState: String,
    val attemptCount: Int,
    val recordedAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
    val lastError: String?,
) {
    fun toModel(): TrackPoint = TrackPoint(
        messageId = messageId,
        deviceId = deviceId,
        sequence = sequence,
        fix = LocationFix(
            fixId = fixId,
            deviceId = deviceId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            source = LocationSource.valueOf(source),
            quality = FixQuality.valueOf(quality),
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            verticalAccuracyMeters = verticalAccuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
            bearingDegrees = bearingDegrees,
            bearingAccuracyDegrees = bearingAccuracyDegrees,
            gnss = GnssQuality(
                satellitesUsed = satellitesUsed,
                satellitesVisible = satellitesVisible,
                pdop = pdop,
                hdop = hdop,
                vdop = vdop,
                correctionAgeSeconds = correctionAgeSeconds,
                correctionStationId = correctionStationId,
            ),
            provider = provider,
            isMock = isMock,
        ),
        deliveryState = DeliveryState.valueOf(deliveryState),
        attemptCount = attemptCount,
    )
}
