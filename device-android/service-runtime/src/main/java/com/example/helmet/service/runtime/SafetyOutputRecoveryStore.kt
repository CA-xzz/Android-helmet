package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent

internal data class SafetyOutputRecoveryLoad(
    val outputs: List<HardwareEvent.Alarm>,
    val discardedEntries: Int,
)

internal class SafetyOutputReplayGate {
    private var currentSessionGeneration: Long? = null
    private val confirmedStates = mutableSetOf<SafetyOutputDesiredState>()

    @Synchronized
    fun pending(
        connected: Boolean,
        simulated: Boolean,
        sessionGeneration: Long,
        outputs: List<HardwareEvent.Alarm>,
    ): List<HardwareEvent.Alarm> {
        if (!connected || simulated) {
            reset()
            return emptyList()
        }
        selectSession(sessionGeneration)
        return outputs.filter { event ->
            val state = event.toRecoverableSafetyOutputState()
            state != null && state !in confirmedStates
        }
    }

    @Synchronized
    fun recordConfirmed(sessionGeneration: Long, event: HardwareEvent.Alarm): Boolean {
        selectSession(sessionGeneration)
        val state = event.toRecoverableSafetyOutputState() ?: return false
        return confirmedStates.add(state)
    }

    @Synchronized
    fun reset() {
        currentSessionGeneration = null
        confirmedStates.clear()
    }

    private fun selectSession(sessionGeneration: Long) {
        if (currentSessionGeneration == sessionGeneration) return
        currentSessionGeneration = sessionGeneration
        confirmedStates.clear()
    }
}

internal fun safetyOutputReplayRetryDelayMillis(failedRound: Int): Long {
    require(failedRound >= 0)
    val shift = failedRound.coerceAtMost(5)
    return (SAFETY_OUTPUT_RETRY_INITIAL_MILLIS shl shift)
        .coerceAtMost(SAFETY_OUTPUT_RETRY_MAX_MILLIS)
}

internal data class SafetyOutputDesiredState(
    val alarmId: Long,
    val alarmType: String,
    val severity: String,
    val active: Boolean,
    val configVersion: Int?,
    val sampleReference: Long?,
    val monotonicMillis: Long,
    val localActions: Int,
    val sensorFaults: Int,
) {
    init {
        require(alarmId > 0)
        require(alarmType in RECOVERABLE_SAFETY_OUTPUT_TYPES)
        require(severity.matches(Regex("[A-Z_]{1,16}")))
        require(configVersion == null || configVersion in 1..0xFFFF)
        require(sampleReference == null || sampleReference in 0..0xFFFF_FFFFL)
        require(monotonicMillis in 0..0xFFFF_FFFFL)
        require(localActions in 1..0xFF)
        require(sensorFaults in 0..0xFFFF)
    }

    fun toHardwareEvent() = HardwareEvent.Alarm(
        monotonicMillis = monotonicMillis,
        alarmType = alarmType,
        severity = severity,
        simulated = false,
        active = active,
        alarmId = alarmId,
        configVersion = configVersion,
        sampleReference = sampleReference,
        localActions = localActions,
        sensorFaults = sensorFaults,
        origin = HardwareAlarmOrigin.ANDROID_DETECTION,
    )

    fun encode(): String = listOf(
        FORMAT_VERSION,
        alarmId,
        alarmType,
        severity,
        if (active) 1 else 0,
        configVersion ?: NULL_FIELD,
        sampleReference ?: NULL_FIELD,
        monotonicMillis,
        localActions,
        sensorFaults,
    ).joinToString(FIELD_SEPARATOR)

    companion object {
        private const val FORMAT_VERSION = 1
        private const val FIELD_SEPARATOR = "|"
        private const val NULL_FIELD = "~"

        fun decode(encoded: String): SafetyOutputDesiredState? = runCatching {
            val fields = encoded.split(FIELD_SEPARATOR)
            require(fields.size == 10 && fields[0].toInt() == FORMAT_VERSION)
            SafetyOutputDesiredState(
                alarmId = fields[1].toLong(),
                alarmType = fields[2],
                severity = fields[3],
                active = when (fields[4]) {
                    "1" -> true
                    "0" -> false
                    else -> error("invalid active state")
                },
                configVersion = fields[5].takeUnless { it == NULL_FIELD }?.toInt(),
                sampleReference = fields[6].takeUnless { it == NULL_FIELD }?.toLong(),
                monotonicMillis = fields[7].toLong(),
                localActions = fields[8].toInt(),
                sensorFaults = fields[9].toInt(),
            )
        }.getOrNull()
    }
}

internal fun HardwareEvent.Alarm.toRecoverableSafetyOutputState(): SafetyOutputDesiredState? {
    if (
        origin != HardwareAlarmOrigin.ANDROID_DETECTION ||
        simulated ||
        localActions == 0 ||
        alarmType !in RECOVERABLE_SAFETY_OUTPUT_TYPES
    ) {
        return null
    }
    val stableAlarmId = alarmId?.takeIf { it > 0 } ?: return null
    return runCatching {
        SafetyOutputDesiredState(
            alarmId = stableAlarmId,
            alarmType = alarmType,
            severity = severity,
            active = active,
            configVersion = configVersion,
            sampleReference = sampleReference,
            monotonicMillis = monotonicMillis,
            localActions = localActions,
            sensorFaults = sensorFaults,
        )
    }.getOrNull()
}

internal fun reconcileSafetyOutputDesiredState(
    persisted: SafetyOutputDesiredState,
    latestRoomState: SafetyOutputDesiredState?,
): SafetyOutputDesiredState = if (persisted.active && latestRoomState?.active == false) {
    latestRoomState
} else {
    persisted
}

internal fun SafetyOutputDesiredState.toFailSafeClear(
    replacementConfigVersion: Int,
): SafetyOutputDesiredState {
    require(replacementConfigVersion in 1..0xFFFF)
    return copy(
        severity = "INFO",
        active = false,
        configVersion = replacementConfigVersion,
    )
}

internal fun SafetyAlertRecord.toRecoverableSafetyOutputState(
    detectionOrigin: String?,
): SafetyOutputDesiredState? {
    if (
        detectionOrigin != HardwareAlarmOrigin.ANDROID_DETECTION.name ||
        simulated ||
        localActions == 0 ||
        alarmType !in RECOVERABLE_SAFETY_OUTPUT_TYPES
    ) {
        return null
    }
    val stableAlarmId = alertId.substringAfterLast(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
    return runCatching {
        SafetyOutputDesiredState(
            alarmId = stableAlarmId,
            alarmType = alarmType,
            severity = severity.name,
            active = active,
            configVersion = configVersion,
            sampleReference = sampleReference,
            monotonicMillis = monotonicMillis,
            localActions = localActions,
            sensorFaults = sensorFaults,
        )
    }.getOrNull()
}

internal fun SafetyAlertRecord.toDetectionEpisodeTermination(
    detectionOrigin: String?,
    replacementConfigVersion: Int,
    replacementSampleReference: Long? = sampleReference,
    replacementMonotonicMillis: Long = monotonicMillis,
): HardwareEvent.Alarm? {
    if (
        detectionOrigin != HardwareAlarmOrigin.ANDROID_DETECTION.name ||
        !active ||
        alarmType !in RECOVERABLE_SAFETY_OUTPUT_TYPES ||
        replacementConfigVersion !in 1..0xFFFF ||
        (replacementSampleReference != null && replacementSampleReference !in 0..0xFFFF_FFFFL) ||
        replacementMonotonicMillis !in 0..0xFFFF_FFFFL
    ) {
        return null
    }
    val stableAlarmId = alertId.substringAfterLast(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
    return HardwareEvent.Alarm(
        monotonicMillis = replacementMonotonicMillis,
        alarmType = alarmType,
        severity = "INFO",
        simulated = simulated,
        active = false,
        alarmId = stableAlarmId,
        configVersion = replacementConfigVersion,
        sampleReference = replacementSampleReference,
        localActions = localActions,
        sensorFaults = sensorFaults,
        origin = HardwareAlarmOrigin.ANDROID_DETECTION,
    )
}

internal fun safetyEpisodeTerminationReferenceAdvances(previous: Long?, replacement: Long): Boolean =
    replacement in 0..0xFFFF_FFFFL && (previous == null || replacement > previous)

internal class SafetyOutputRecoveryStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun load(): SafetyOutputRecoveryLoad {
        val outputs = mutableListOf<HardwareEvent.Alarm>()
        val invalidKeys = mutableListOf<String>()
        preferences.all.forEach { (key, raw) ->
            val state = (raw as? String)?.let(SafetyOutputDesiredState::decode)
            if (state == null || key != state.alarmId.toString()) {
                invalidKeys += key
            } else {
                outputs += state.toHardwareEvent()
            }
        }
        if (invalidKeys.isNotEmpty()) {
            check(preferences.edit().also { editor -> invalidKeys.forEach(editor::remove) }.commit()) {
                "failed to quarantine invalid safety output recovery state"
            }
        }
        return SafetyOutputRecoveryLoad(
            outputs = outputs.sortedBy { it.alarmId },
            discardedEntries = invalidKeys.size,
        )
    }

    fun remember(event: HardwareEvent.Alarm): Boolean {
        val state = requireNotNull(event.toRecoverableSafetyOutputState())
        return preferences.edit().putString(state.alarmId.toString(), state.encode()).commit()
    }

    fun removeAfterConfirmedClear(event: HardwareEvent.Alarm): Boolean {
        val state = requireNotNull(event.toRecoverableSafetyOutputState())
        require(!state.active)
        return remove(state.alarmId)
    }

    fun remove(alarmId: Long): Boolean = preferences.edit().remove(alarmId.toString()).commit()

    companion object {
        private const val PREFERENCES_NAME = "helmet_safety_output_recovery"
    }
}

internal val RECOVERABLE_SAFETY_OUTPUT_TYPES = setOf(
    "NEAR_ELECTRIC",
    "HEIGHT_LIMIT",
    "SENSOR_FAULT",
    "INACTIVITY",
)

private const val SAFETY_OUTPUT_RETRY_INITIAL_MILLIS = 1_000L
private const val SAFETY_OUTPUT_RETRY_MAX_MILLIS = 30_000L
