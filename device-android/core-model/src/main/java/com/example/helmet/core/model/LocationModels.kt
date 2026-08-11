package com.example.helmet.core.model

enum class LocationSource {
    ANDROID_GNSS,
    ANDROID_FUSED,
    EXTERNAL_NMEA,
    CELL_ASSISTED,
    REPLAY,
}

enum class FixQuality {
    NO_FIX,
    UNVALIDATED,
    STANDARD,
    DIFFERENTIAL,
    RTK_FLOAT,
    RTK_FIXED,
    DEAD_RECKONING,
}

data class GnssQuality(
    val satellitesUsed: Int? = null,
    val satellitesVisible: Int? = null,
    val pdop: Double? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,
    val correctionAgeSeconds: Double? = null,
    val correctionStationId: String? = null,
) {
    init {
        require(satellitesUsed == null || satellitesUsed >= 0)
        require(satellitesVisible == null || satellitesVisible >= 0)
        require(satellitesUsed == null || satellitesVisible == null || satellitesUsed <= satellitesVisible)
        require(pdop == null || pdop >= 0)
        require(hdop == null || hdop >= 0)
        require(vdop == null || vdop >= 0)
        require(correctionAgeSeconds == null || correctionAgeSeconds >= 0)
    }
}

data class LocationFix(
    val fixId: String,
    val deviceId: String,
    val occurredAtEpochMillis: Long,
    val elapsedRealtimeNanos: Long?,
    val source: LocationSource,
    val quality: FixQuality,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Double? = null,
    val horizontalAccuracyMeters: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val gnss: GnssQuality = GnssQuality(),
    val provider: String? = null,
    val isMock: Boolean = false,
) {
    init {
        require(fixId.isNotBlank())
        require(deviceId.isNotBlank())
        require(occurredAtEpochMillis > 0)
        require(elapsedRealtimeNanos == null || elapsedRealtimeNanos >= 0)
        if (quality == FixQuality.NO_FIX) {
            require(latitude == null && longitude == null) { "NO_FIX must not contain coordinates" }
        } else {
            require(latitude != null && latitude in -90.0..90.0) { "invalid latitude" }
            require(longitude != null && longitude in -180.0..180.0) { "invalid longitude" }
        }
        require(horizontalAccuracyMeters == null || horizontalAccuracyMeters >= 0)
        require(verticalAccuracyMeters == null || verticalAccuracyMeters >= 0)
        require(speedMetersPerSecond == null || speedMetersPerSecond >= 0)
        require(speedAccuracyMetersPerSecond == null || speedAccuracyMetersPerSecond >= 0)
        require(bearingDegrees == null || bearingDegrees in 0f..360f)
        require(bearingAccuracyDegrees == null || bearingAccuracyDegrees >= 0)
    }

    val hasPosition: Boolean
        get() = quality != FixQuality.NO_FIX && latitude != null && longitude != null
}

data class TrackPoint(
    val messageId: String,
    val deviceId: String,
    val sequence: Long,
    val fix: LocationFix,
    val deliveryState: DeliveryState,
    val attemptCount: Int,
) {
    init {
        require(messageId.isNotBlank())
        require(deviceId == fix.deviceId)
        require(sequence > 0)
        require(fix.hasPosition)
        require(attemptCount >= 0)
    }
}

data class CircleGeofence(
    val geofenceId: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val hysteresisMeters: Double = 5.0,
    val confirmationSamples: Int = 2,
) {
    init {
        require(geofenceId.isNotBlank())
        require(centerLatitude in -90.0..90.0)
        require(centerLongitude in -180.0..180.0)
        require(radiusMeters > 0)
        require(hysteresisMeters >= 0 && hysteresisMeters < radiusMeters)
        require(confirmationSamples > 0)
    }
}

enum class GeofenceTransitionType {
    ENTER,
    EXIT,
}

data class GeofenceTransition(
    val geofenceId: String,
    val type: GeofenceTransitionType,
    val fixId: String,
    val occurredAtEpochMillis: Long,
    val distanceMeters: Double,
)
