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
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun locationFailureCode(error: Throwable): String = error.javaClass.name.take(160)

internal data class AndroidNmeaQualitySnapshot(
    val fixQuality: FixQuality?,
    val gnss: GnssQuality,
)

internal class AndroidNmeaQualityTracker(
    private val monotonicClockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val freshnessMillis: Long = 2_000L,
) {
    private data class TimedSentence<T : NmeaSentence>(
        val receivedAtElapsedMillis: Long,
        val sentence: T,
    )

    init {
        require(freshnessMillis > 0)
    }

    private var latestGga: TimedSentence<NmeaSentence.Gga>? = null
    private var latestGsa: TimedSentence<NmeaSentence.Gsa>? = null
    private var latestGsv: TimedSentence<NmeaSentence.Gsv>? = null

    @Synchronized
    fun accept(sentence: NmeaSentence) {
        val timed = monotonicClockMillis()
        when (sentence) {
            is NmeaSentence.Gga -> latestGga = TimedSentence(timed, sentence)
            is NmeaSentence.Gsa -> latestGsa = TimedSentence(timed, sentence)
            is NmeaSentence.Gsv -> latestGsv = TimedSentence(timed, sentence)
            is NmeaSentence.Rmc -> Unit
        }
    }

    @Synchronized
    fun snapshot(): AndroidNmeaQualitySnapshot {
        val now = monotonicClockMillis()
        val gga = latestGga.fresh(now)
        val gsa = latestGsa.fresh(now)
        val gsv = latestGsv.fresh(now)
        val satellitesUsed = gga?.satellitesUsed ?: gsa?.satelliteIds?.size
        val satellitesVisible = gsv?.satellitesVisible?.let { visible ->
            maxOf(visible, satellitesUsed ?: 0)
        }
        return AndroidNmeaQualitySnapshot(
            fixQuality = gga?.quality,
            gnss = GnssQuality(
                satellitesUsed = satellitesUsed,
                satellitesVisible = satellitesVisible,
                pdop = gsa?.pdop,
                hdop = gga?.hdop ?: gsa?.hdop,
                vdop = gsa?.vdop,
                correctionAgeSeconds = gga?.correctionAgeSeconds,
                correctionStationId = gga?.correctionStationId,
            ),
        )
    }

    private fun <T : NmeaSentence> TimedSentence<T>?.fresh(now: Long): T? =
        this?.takeIf { now - it.receivedAtElapsedMillis in 0..freshnessMillis }?.sentence
}

internal fun androidLocationFixQuality(
    source: LocationSource,
    isMock: Boolean,
    nmeaFixQuality: FixQuality?,
): FixQuality = when {
    isMock -> FixQuality.UNVALIDATED
    source != LocationSource.ANDROID_GNSS -> FixQuality.UNVALIDATED
    nmeaFixQuality == FixQuality.NO_FIX -> FixQuality.UNVALIDATED
    nmeaFixQuality != null -> nmeaFixQuality
    else -> FixQuality.STANDARD
}

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
    val fixQueueOverflowCount: Long = 0,
)

internal data class LocationFixQueueOffer(
    val accepted: Boolean,
    val overflowCount: Long,
)

internal class LocationFixQueue(capacity: Int) {
    private val channel = Channel<LocationFix>(capacity)
    private val overflows = AtomicLong()

    init {
        require(capacity > 0)
    }

    val fixes: Flow<LocationFix> = channel.receiveAsFlow()

    fun offer(fix: LocationFix): LocationFixQueueOffer {
        val accepted = channel.trySend(fix).isSuccess
        return LocationFixQueueOffer(
            accepted = accepted,
            overflowCount = if (accepted) overflows.get() else overflows.incrementAndGet(),
        )
    }

    fun close() = channel.close()
}

class AndroidLocationController(
    context: Context,
    private val deviceId: String,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanosClock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(LocationManager::class.java)
    private val inspector = LocationCapabilityInspector(applicationContext)
    private val thread = HandlerThread("helmet-location").apply { start() }
    private val handler = Handler(thread.looper)
    private val operationMutex = Mutex()
    private val fixQueue = LocationFixQueue(FIX_QUEUE_CAPACITY)
    private val _status = MutableStateFlow(LocationControllerStatus())

    @Volatile
    private var latestGnssStatus = GnssQuality()

    private val nmeaQualityTracker = AndroidNmeaQualityTracker()

    @Volatile
    private var running = false

    val fixes: Flow<LocationFix> = fixQueue.fixes
    val status: StateFlow<LocationControllerStatus> = _status

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = location.toFix()
            val offer = fixQueue.offer(fix)
            _status.value = if (offer.accepted) {
                _status.value.copy(
                    state = LocationControllerState.TRACKING,
                    lastFixQuality = fix.quality,
                    lastFixAtEpochMillis = fix.occurredAtEpochMillis,
                    lastError = null,
                )
            } else {
                _status.value.copy(
                    lastError = "location fix queue full; rejected fixes=${offer.overflowCount}",
                    fixQueueOverflowCount = offer.overflowCount,
                )
            }
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
        NmeaParser.parse(message)?.let(nmeaQualityTracker::accept)
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
                fixQueueOverflowCount = _status.value.fixQueueOverflowCount,
            )
            return
        }
        val provider = selectProvider(capabilities)
        if (provider == null) {
            _status.value = LocationControllerStatus(
                state = LocationControllerState.NO_PROVIDER,
                hasGnssProvider = capabilities.hasGnssProvider,
                lastError = "no enabled GNSS, fused, or network provider",
                fixQueueOverflowCount = _status.value.fixQueueOverflowCount,
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
                fixQueueOverflowCount = _status.value.fixQueueOverflowCount,
            )
        } catch (error: Throwable) {
            _status.value = LocationControllerStatus(
                state = LocationControllerState.ERROR,
                selectedProvider = provider,
                hasGnssProvider = capabilities.hasGnssProvider,
                lastError = locationFailureCode(error),
                fixQueueOverflowCount = _status.value.fixQueueOverflowCount,
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
        fixQueue.close()
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
        val nmea = nmeaQualityTracker.snapshot()
        val quality = androidLocationFixQuality(source, isMock, nmea.fixQuality)
        val gnss = GnssQuality(
            satellitesUsed = nmea.gnss.satellitesUsed ?: latestGnssStatus.satellitesUsed,
            satellitesVisible = maxOf(
                nmea.gnss.satellitesVisible ?: 0,
                nmea.gnss.satellitesUsed ?: latestGnssStatus.satellitesUsed ?: 0,
                latestGnssStatus.satellitesVisible ?: 0,
            ).takeIf { it > 0 },
            pdop = nmea.gnss.pdop,
            hdop = nmea.gnss.hdop,
            vdop = nmea.gnss.vdop,
            correctionAgeSeconds = nmea.gnss.correctionAgeSeconds,
            correctionStationId = nmea.gnss.correctionStationId,
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
        private const val FIX_QUEUE_CAPACITY = 64
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
