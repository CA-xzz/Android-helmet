package com.example.helmet.data.local

import androidx.room.withTransaction
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.TrackPoint
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TrackStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxRetainedTerminalPointsPerDevice: Int = DEFAULT_MAX_RETAINED_TERMINAL_POINTS,
) {
    init {
        require(maxRetainedTerminalPointsPerDevice > 0)
    }

    suspend fun record(fix: LocationFix, messageId: String = UUID.randomUUID().toString()): TrackPoint? {
        require(fix.hasPosition) { "cannot record a location without a position" }
        require(!fix.isMock) { "mock locations cannot enter the production track queue" }
        return database.withTransaction {
            database.trackPointDao().find(messageId)?.let { existing ->
                require(existing.deviceId == fix.deviceId && existing.toModel().fix == fix) {
                    "track message ID conflicts with stored content"
                }
                return@withTransaction null
            }
            val maximumSequence = database.trackPointDao().maxSequence(fix.deviceId)
            check(maximumSequence < Long.MAX_VALUE) { "track sequence is exhausted" }
            val sequence = maximumSequence + 1L
            val point = TrackPoint(
                messageId = messageId,
                deviceId = fix.deviceId,
                sequence = sequence,
                fix = fix,
                deliveryState = DeliveryState.PENDING,
                attemptCount = 0,
            )
            check(database.trackPointDao().insert(point.toEntity(wallClock())) != -1L) {
                "track insert conflicted after sequence allocation"
            }
            point
        }
    }

    fun observeRecent(limit: Int = 100): Flow<List<TrackPoint>> =
        database.trackPointDao().observeRecent(limit).map { rows -> rows.map(TrackPointEntity::toModel) }

    suspend fun pending(limit: Int = 200): List<TrackPoint> =
        database.trackPointDao().pending(limit).map(TrackPointEntity::toModel)

    suspend fun pendingCount(): Int = database.trackPointDao().pendingCount()

    suspend fun find(messageId: String): TrackPoint? = database.trackPointDao().find(messageId)?.toModel()

    suspend fun markAttempt(messageId: String, attemptAt: Long): Boolean =
        database.trackPointDao().markAttempt(messageId, attemptAt) == 1

    suspend fun markDelivered(messageId: String, deliveredAt: Long): Boolean =
        database.withTransaction {
            val changed = database.trackPointDao().markDelivered(messageId, deliveredAt) == 1
            if (changed) {
                val deviceId = requireNotNull(database.trackPointDao().find(messageId)).deviceId
                database.trackPointDao().pruneTerminal(
                    deviceId,
                    maxRetainedTerminalPointsPerDevice,
                )
            }
            changed
        }

    suspend fun markFailed(messageId: String, error: String): Boolean =
        database.trackPointDao().markFailed(messageId, error.take(MAX_ERROR_LENGTH)) == 1

    suspend fun markRejected(messageId: String, error: String): Boolean =
        database.withTransaction {
            val changed = database.trackPointDao().markRejected(
                messageId,
                error.take(MAX_ERROR_LENGTH),
            ) == 1
            if (changed) {
                val deviceId = requireNotNull(database.trackPointDao().find(messageId)).deviceId
                database.trackPointDao().pruneTerminal(
                    deviceId,
                    maxRetainedTerminalPointsPerDevice,
                )
            }
            changed
        }

    private fun TrackPoint.toEntity(recordedAt: Long): TrackPointEntity = TrackPointEntity(
        messageId = messageId,
        deviceId = deviceId,
        sequence = sequence,
        fixId = fix.fixId,
        occurredAtEpochMillis = fix.occurredAtEpochMillis,
        elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
        source = fix.source.name,
        quality = fix.quality.name,
        latitude = requireNotNull(fix.latitude),
        longitude = requireNotNull(fix.longitude),
        altitudeMeters = fix.altitudeMeters,
        horizontalAccuracyMeters = fix.horizontalAccuracyMeters,
        verticalAccuracyMeters = fix.verticalAccuracyMeters,
        speedMetersPerSecond = fix.speedMetersPerSecond,
        speedAccuracyMetersPerSecond = fix.speedAccuracyMetersPerSecond,
        bearingDegrees = fix.bearingDegrees,
        bearingAccuracyDegrees = fix.bearingAccuracyDegrees,
        satellitesUsed = fix.gnss.satellitesUsed,
        satellitesVisible = fix.gnss.satellitesVisible,
        pdop = fix.gnss.pdop,
        hdop = fix.gnss.hdop,
        vdop = fix.gnss.vdop,
        correctionAgeSeconds = fix.gnss.correctionAgeSeconds,
        correctionStationId = fix.gnss.correctionStationId,
        provider = fix.provider,
        isMock = fix.isMock,
        deliveryState = deliveryState.name,
        attemptCount = attemptCount,
        recordedAtEpochMillis = recordedAt,
        lastAttemptAtEpochMillis = null,
        deliveredAtEpochMillis = null,
        lastError = null,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
        const val DEFAULT_MAX_RETAINED_TERMINAL_POINTS = 20_000
    }
}
