package com.example.helmet.feature.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocationControllerState {
    STOPPED,
    PERMISSION_REQUIRED,
    NO_PROVIDER,
    WAITING_FOR_FIX,
    TRACKING,
    ERROR,
}

data class LocationControllerStatus(
    val state: LocationControllerState = LocationControllerState.STOPPED,
    val selectedProvider: String? = null,
    val hasGnssProvider: Boolean = false,
    val lastFixQuality: FixQuality = FixQuality.NO_FIX,
    val lastFixAtEpochMillis: Long? = null,
    val lastError: String? = null,
)

class AndroidLocationController(
    context: Context,
    private val deviceId: String,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanosClock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private data class NmeaQualitySnapshot(
        val receivedAtElapsedMillis: Long,
        val fixQuality: FixQuality? = null,
        val satellitesUsed: Int? = null,
        val satellitesVisible: Int? = null,
        val pdop: Double? = null,
        val hdop: Double? = null,
        val vdop: Double? = null,
        val correctionAgeSeconds: Double? = null,
        val correctionStationId: String? = null,
    )

    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(LocationManager::class.java)
    private val inspector = LocationCapabilityInspector(applicationContext)
    private val thread = HandlerThread("helmet-location").apply { start() }
    private val handler = Handler(thread.looper)
    private val operationMutex = Mutex()
    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    private val _status = MutableStateFlow(LocationControllerStatus())

    @Volatile
    private var latestGnssStatus = GnssQuality()

    @Volatile
    private var latestNmea = NmeaQualitySnapshot(0)

    @Volatile
    private var running = false

    val fixes: SharedFlow<LocationFix> = _fixes
    val status: StateFlow<LocationControllerStatus> = _status

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = location.toFix()
            _fixes.tryEmit(fix)
            _status.value = _status.value.copy(
                state = LocationControllerState.TRACKING,
                lastFixQuality = fix.quality,
                lastFixAtEpochMillis = fix.occurredAtEpochMillis,
                lastError = null,
            )
        }

        override fun onProviderDisabled(provider: String) {
            _status.value = _status.value.copy(
                state = LocationControllerState.NO_PROVIDER,
                lastError = "provider disabled: $provider",
            )
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used += 1
            }
            latestGnssStatus = latestGnssStatus.copy(
                satellitesUsed = used,
                satellitesVisible = status.satelliteCount,
            )
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        val now = SystemClock.elapsedRealtime()
        latestNmea = when (val sentence = NmeaParser.parse(message)) {
            is NmeaSentence.Gga -> latestNmea.copy(
                receivedAtElapsedMillis = now,
                fixQuality = sentence.quality,
                satellitesUsed = sentence.satellitesUsed,
                hdop = sentence.hdop,
                correctionAgeSeconds = sentence.correctionAgeSeconds,
                correctionStationId = sentence.correctionStationId,
            )
            is NmeaSentence.Gsa -> latestNmea.copy(
                receivedAtElapsedMillis = now,
                satellitesUsed = sentence.satelliteIds.size,
                pdop = sentence.pdop,
                hdop = sentence.hdop,
                vdop = sentence.vdop,
            )
            is NmeaSentence.Gsv -> latestNmea.copy(
                receivedAtElapsedMillis = now,
                satellitesVisible = sentence.satellitesVisible,
            )
            else -> latestNmea
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun start() = operationMutex.withLock {
        if (running) return
        val capabilities = inspector.inspect()
        if (!capabilities.hasFinePermission && !capabilities.hasCoarsePermission) {
            _status.value = LocationControllerStatus(
                state = LocationControllerState.PERMISSION_REQUIRED,
                hasGnssProvider = capabilities.hasGnssProvider,
                lastError = "location permission is not granted",
            )
            return
        }
        val provider = selectProvider(capabilities)
        if (provider == null) {
            _status.value = LocationControllerStatus(
                state = LocationControllerState.NO_PROVIDER,
                hasGnssProvider = capabilities.hasGnssProvider,
                lastError = "no enabled GNSS, fused, or network provider",
            )
            return
        }
        try {
            manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS, 0f, locationListener, thread.looper)
            if (capabilities.hasGnssProvider && capabilities.hasFinePermission) {
                manager.registerGnssStatusCallback(gnssCallback, handler)
                manager.addNmeaListener(nmeaListener, handler)
            }
            running = true
            _status.value = LocationControllerStatus(
                state = LocationControllerState.WAITING_FOR_FIX,
                selectedProvider = provider,
                hasGnssProvider = capabilities.hasGnssProvider,
            )
        } catch (error: Throwable) {
            _status.value = LocationControllerStatus(
                state = LocationControllerState.ERROR,
                selectedProvider = provider,
                hasGnssProvider = capabilities.hasGnssProvider,
                lastError = error.toString(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun stop() = operationMutex.withLock {
        if (running) {
            runCatching { manager.removeUpdates(locationListener) }
            runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
            runCatching { manager.removeNmeaListener(nmeaListener) }
        }
        running = false
        _status.value = _status.value.copy(state = LocationControllerState.STOPPED)
    }

    override fun close() {
        if (running) {
            runCatching { manager.removeUpdates(locationListener) }
            runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
            runCatching { manager.removeNmeaListener(nmeaListener) }
        }
        running = false
        thread.quitSafely()
    }

    private fun selectProvider(capabilities: LocationCapabilities): String? = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).firstOrNull { provider -> provider in capabilities.enabledProviders }

    private fun Location.toFix(): LocationFix {
        val observedAtElapsedRealtimeNanos = elapsedRealtimeNanosClock()
        val fixElapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0 }
            ?: observedAtElapsedRealtimeNanos
        val occurredAtEpochMillis = locationEpochFromMonotonicAge(
            epochClock(),
            observedAtElapsedRealtimeNanos,
            fixElapsedRealtimeNanos,
        )
        val source = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationSource.ANDROID_GNSS
            LocationManager.NETWORK_PROVIDER -> LocationSource.CELL_ASSISTED
            else -> LocationSource.ANDROID_FUSED
        }
        val nmea = latestNmea.takeIf {
            SystemClock.elapsedRealtime() - it.receivedAtElapsedMillis <= NMEA_FRESHNESS_MILLIS
        }
        val quality = when {
            isMock -> FixQuality.UNVALIDATED
            source == LocationSource.ANDROID_GNSS -> nmea?.fixQuality ?: FixQuality.STANDARD
            else -> FixQuality.UNVALIDATED
        }
        val gnss = GnssQuality(
            satellitesUsed = nmea?.satellitesUsed ?: latestGnssStatus.satellitesUsed,
            satellitesVisible = nmea?.satellitesVisible ?: latestGnssStatus.satellitesVisible,
            pdop = nmea?.pdop,
            hdop = nmea?.hdop,
            vdop = nmea?.vdop,
            correctionAgeSeconds = nmea?.correctionAgeSeconds,
            correctionStationId = nmea?.correctionStationId,
        )
        return LocationFix(
            fixId = idFactory(),
            deviceId = deviceId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            elapsedRealtimeNanos = fixElapsedRealtimeNanos,
            source = source,
            quality = quality,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitude.takeIf { hasAltitude() },
            horizontalAccuracyMeters = accuracy.takeIf { hasAccuracy() },
            verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
            speedMetersPerSecond = speed.takeIf { hasSpeed() },
            speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() },
            bearingDegrees = bearing.takeIf { hasBearing() },
            bearingAccuracyDegrees = bearingAccuracyDegrees.takeIf { hasBearingAccuracy() },
            gnss = gnss,
            provider = provider,
            isMock = isMock,
        )
    }

    companion object {
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val NMEA_FRESHNESS_MILLIS = 2_000L
    }
}

internal fun locationEpochFromMonotonicAge(
    observedAtEpochMillis: Long,
    observedAtElapsedRealtimeNanos: Long,
    fixElapsedRealtimeNanos: Long,
): Long {
    require(observedAtEpochMillis > 0)
    val ageMillis = ((observedAtElapsedRealtimeNanos - fixElapsedRealtimeNanos)
        .coerceAtLeast(0) / 1_000_000L)
    return (observedAtEpochMillis - ageMillis).coerceAtLeast(1)
}
