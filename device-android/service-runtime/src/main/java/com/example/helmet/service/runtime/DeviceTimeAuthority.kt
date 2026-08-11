package com.example.helmet.service.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import kotlin.math.ceil

enum class DeviceTimeSource {
    SYSTEM,
    SERVER,
}

data class DeviceTimeReading(
    val epochMillis: Long,
    val source: DeviceTimeSource,
    val synchronized: Boolean,
    val uncertaintyMillis: Long?,
    val calibrationAgeMillis: Long?,
    val calibrationSequence: Long?,
)

internal data class DeviceTimeAdjustment(
    val previous: DeviceTimeReading,
    val current: DeviceTimeReading,
    val correctionMillis: Long,
)

internal data class DeviceTimeCalibrationResult(
    val reading: DeviceTimeReading,
    val significantAdjustment: DeviceTimeAdjustment?,
)

internal data class DeviceTimeCalibration(
    val source: DeviceTimeSource,
    val epochAtReferenceMillis: Long,
    val elapsedRealtimeAtReferenceMillis: Long,
    val initialUncertaintyMillis: Long,
    val bootSessionId: String,
    val sequence: Long,
)

internal interface DeviceTimeCalibrationStore {
    fun load(): DeviceTimeCalibration?
    fun save(calibration: DeviceTimeCalibration): Boolean
}

internal class DeviceTimeDiscipline(
    private val store: DeviceTimeCalibrationStore,
    private val bootSessionId: String,
    private val wallClock: () -> Long,
    private val elapsedRealtimeClock: () -> Long,
    private val maximumRoundTripMillis: Long = 30_000,
) {
    fun read(): DeviceTimeReading {
        val elapsedNow = elapsedRealtimeClock()
        val calibration = store.load()?.takeIf {
            it.source != DeviceTimeSource.SYSTEM &&
                it.bootSessionId == bootSessionId &&
                elapsedNow >= it.elapsedRealtimeAtReferenceMillis
        }
        if (calibration == null) {
            return DeviceTimeReading(
                epochMillis = wallClock().coerceAtLeast(1),
                source = DeviceTimeSource.SYSTEM,
                synchronized = false,
                uncertaintyMillis = null,
                calibrationAgeMillis = null,
                calibrationSequence = null,
            )
        }
        val age = elapsedNow - calibration.elapsedRealtimeAtReferenceMillis
        val driftUncertainty = ceil(age * ASSUMED_MAXIMUM_DRIFT_PARTS_PER_MILLION / 1_000_000.0).toLong()
        return DeviceTimeReading(
            epochMillis = calibration.epochAtReferenceMillis + age,
            source = calibration.source,
            synchronized = true,
            uncertaintyMillis = calibration.initialUncertaintyMillis + driftUncertainty,
            calibrationAgeMillis = age,
            calibrationSequence = calibration.sequence,
        )
    }

    fun calibrateFromServer(
        serverReceivedAtEpochMillis: Long,
        requestStartedAtElapsedRealtimeMillis: Long,
        responseReceivedAtElapsedRealtimeMillis: Long,
    ): DeviceTimeReading {
        require(serverReceivedAtEpochMillis in MINIMUM_TRUSTED_EPOCH_MILLIS..MAXIMUM_TRUSTED_EPOCH_MILLIS) {
            "server time is outside the accepted range"
        }
        require(requestStartedAtElapsedRealtimeMillis >= 0)
        require(responseReceivedAtElapsedRealtimeMillis >= requestStartedAtElapsedRealtimeMillis)
        val roundTrip = responseReceivedAtElapsedRealtimeMillis - requestStartedAtElapsedRealtimeMillis
        require(roundTrip <= maximumRoundTripMillis) { "server time round trip is too large" }
        val previousSequence = store.load()?.sequence ?: 0
        val halfRoundTrip = roundTrip / 2
        val calibration = DeviceTimeCalibration(
            source = DeviceTimeSource.SERVER,
            epochAtReferenceMillis = serverReceivedAtEpochMillis + halfRoundTrip,
            elapsedRealtimeAtReferenceMillis = responseReceivedAtElapsedRealtimeMillis,
            initialUncertaintyMillis = maxOf(1, (roundTrip + 1) / 2),
            bootSessionId = bootSessionId,
            sequence = previousSequence + 1,
        )
        check(store.save(calibration)) { "failed to persist device time calibration" }
        return read()
    }

    companion object {
        private const val MINIMUM_TRUSTED_EPOCH_MILLIS = 1_577_836_800_000L
        private const val MAXIMUM_TRUSTED_EPOCH_MILLIS = 4_102_444_800_000L
        private const val ASSUMED_MAXIMUM_DRIFT_PARTS_PER_MILLION = 100L
    }
}

class DeviceTimeAuthority internal constructor(
    private val discipline: DeviceTimeDiscipline,
) {
    private val mutableSignificantAdjustments = MutableSharedFlow<DeviceTimeAdjustment>(
        extraBufferCapacity = 1,
    )

    internal val significantAdjustments: SharedFlow<DeviceTimeAdjustment> =
        mutableSignificantAdjustments.asSharedFlow()

    fun read(): DeviceTimeReading = discipline.read()

    fun nowEpochMillis(): Long = read().epochMillis

    internal fun calibrateFromServer(
        serverReceivedAtEpochMillis: Long,
        requestStartedAtElapsedRealtimeMillis: Long,
        responseReceivedAtElapsedRealtimeMillis: Long,
    ): DeviceTimeCalibrationResult {
        val previous = discipline.read()
        val current = discipline.calibrateFromServer(
            serverReceivedAtEpochMillis,
            requestStartedAtElapsedRealtimeMillis,
            responseReceivedAtElapsedRealtimeMillis,
        )
        return DeviceTimeCalibrationResult(
            reading = current,
            significantAdjustment = if (shouldPublishTimeAdjustment(previous, current)) {
                DeviceTimeAdjustment(
                    previous = previous,
                    current = current,
                    correctionMillis = current.epochMillis - previous.epochMillis,
                )
            } else {
                null
            },
        )
    }

    internal fun announceSignificantAdjustment(result: DeviceTimeCalibrationResult) {
        result.significantAdjustment?.let(mutableSignificantAdjustments::tryEmit)
    }
}

internal fun shouldPublishTimeAdjustment(
    previous: DeviceTimeReading,
    current: DeviceTimeReading,
): Boolean {
    if (previous.source != current.source || previous.synchronized != current.synchronized) return true
    val previousUncertainty = previous.uncertaintyMillis ?: 0L
    val currentUncertainty = current.uncertaintyMillis ?: 0L
    val combinedUncertainty = if (previousUncertainty > Long.MAX_VALUE - currentUncertainty) {
        Long.MAX_VALUE
    } else {
        previousUncertainty + currentUncertainty
    }
    val threshold = maxOf(MINIMUM_SIGNIFICANT_TIME_CORRECTION_MILLIS, combinedUncertainty)
    val correction = current.epochMillis - previous.epochMillis
    return correction > threshold || correction < -threshold
}

private const val MINIMUM_SIGNIFICANT_TIME_CORRECTION_MILLIS = 1_000L

object DeviceTimeAuthorityProvider {
    @Volatile
    private var instance: DeviceTimeAuthority? = null

    fun get(context: Context): DeviceTimeAuthority = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): DeviceTimeAuthority = DeviceTimeAuthority(
        DeviceTimeDiscipline(
            store = SharedPreferencesDeviceTimeCalibrationStore(context),
            bootSessionId = bootSessionId(context),
            wallClock = System::currentTimeMillis,
            elapsedRealtimeClock = SystemClock::elapsedRealtime,
        ),
    )

    private fun bootSessionId(context: Context): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf(String::isNotBlank)
    }.getOrNull() ?: "${Build.FINGERPRINT}:${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)}"
}

private class SharedPreferencesDeviceTimeCalibrationStore(context: Context) : DeviceTimeCalibrationStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): DeviceTimeCalibration? {
        val source = preferences.getString(KEY_SOURCE, null)?.let {
            runCatching { DeviceTimeSource.valueOf(it) }.getOrNull()
        } ?: return null
        if (source == DeviceTimeSource.SYSTEM) return null
        val epoch = preferences.getLong(KEY_EPOCH_AT_REFERENCE, 0)
        val elapsed = preferences.getLong(KEY_ELAPSED_AT_REFERENCE, -1)
        val uncertainty = preferences.getLong(KEY_INITIAL_UNCERTAINTY, -1)
        val boot = preferences.getString(KEY_BOOT_SESSION, null) ?: return null
        val sequence = preferences.getLong(KEY_SEQUENCE, 0)
        if (epoch <= 0 || elapsed < 0 || uncertainty < 0 || sequence <= 0) return null
        return DeviceTimeCalibration(source, epoch, elapsed, uncertainty, boot, sequence)
    }

    override fun save(calibration: DeviceTimeCalibration): Boolean = preferences.edit()
        .putString(KEY_SOURCE, calibration.source.name)
        .putLong(KEY_EPOCH_AT_REFERENCE, calibration.epochAtReferenceMillis)
        .putLong(KEY_ELAPSED_AT_REFERENCE, calibration.elapsedRealtimeAtReferenceMillis)
        .putLong(KEY_INITIAL_UNCERTAINTY, calibration.initialUncertaintyMillis)
        .putString(KEY_BOOT_SESSION, calibration.bootSessionId)
        .putLong(KEY_SEQUENCE, calibration.sequence)
        .commit()

    companion object {
        private const val PREFERENCES = "helmet_device_time"
        private const val KEY_SOURCE = "source"
        private const val KEY_EPOCH_AT_REFERENCE = "epoch_at_reference_millis"
        private const val KEY_ELAPSED_AT_REFERENCE = "elapsed_at_reference_millis"
        private const val KEY_INITIAL_UNCERTAINTY = "initial_uncertainty_millis"
        private const val KEY_BOOT_SESSION = "boot_session_id"
        private const val KEY_SEQUENCE = "sequence"
    }
}
