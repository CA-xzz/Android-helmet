package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.model.canonicalFingerprint
import com.example.helmet.data.local.SafetyDetectionCheckpointRecord
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.safety.detection.SafetyAlarmType
import com.example.helmet.safety.detection.SafetyDecision
import com.example.helmet.safety.detection.SafetyDetectionEngine
import com.example.helmet.safety.detection.SafetyEvaluation
import com.example.helmet.safety.detection.SafetySensorSample
import com.example.helmet.safety.detection.SafetySeverity

internal data class EvaluatedSafetyAlarm(
    val event: HardwareEvent.Alarm,
    val evaluation: SafetyEvaluation,
    val decision: SafetyDecision,
)

internal data class SafetySampleProcessingResult(
    val evaluation: SafetyEvaluation,
    val alarms: List<EvaluatedSafetyAlarm>,
    val engineReset: Boolean,
)

internal data class SafetyStateRestoreResult(
    val restoredSamples: Int,
    val rejectedSamples: Int,
    val clockResets: Int,
    val alarms: List<EvaluatedSafetyAlarm>,
)

internal class SafetySampleProcessor(
    private val config: SafetyThresholdConfig,
) {
    private val configFingerprint = config.canonicalFingerprint()
    private data class EpisodeKey(
        val type: SafetyAlarmType,
        val sensorFaults: Int,
    )

    private val engine = SafetyDetectionEngine(config)
    private val activeEpisodes = mutableMapOf<EpisodeKey, ActiveSafetyEpisodeCheckpoint>()
    private var lastMonotonicMillis: Long? = null
    private var lastSampleReference: Long? = null

    fun restore(samples: List<SafetySensorTelemetry>): SafetyStateRestoreResult {
        engine.reset()
        activeEpisodes.clear()
        lastMonotonicMillis = null
        lastSampleReference = null
        var restored = 0
        var rejected = 0
        var resets = 0
        val alarms = mutableListOf<EvaluatedSafetyAlarm>()
        samples.forEach { telemetry ->
            runCatching { process(telemetry) }
                .onSuccess { result ->
                    restored += 1
                    if (result.engineReset) resets += 1
                    alarms += result.alarms
                }
                .onFailure { rejected += 1 }
        }
        return SafetyStateRestoreResult(
            restoredSamples = restored,
            rejectedSamples = rejected,
            clockResets = resets,
            alarms = alarms,
        )
    }

    fun restoreCheckpoint(record: SafetyDetectionCheckpointRecord) {
        val checkpoint = SafetyProcessorCheckpoint.decode(record)
        require(checkpoint.thresholdConfigVersion == config.version) {
            "safety threshold configuration version changed"
        }
        require(checkpoint.thresholdConfigFingerprint == configFingerprint) {
            "safety threshold configuration content changed"
        }
        engine.restore(checkpoint.engine)
        activeEpisodes.clear()
        checkpoint.activeEpisodes.forEach { episode ->
            activeEpisodes[EpisodeKey(episode.type, episode.sensorFaults)] = episode
        }
        lastMonotonicMillis = requireNotNull(checkpoint.engine.lastMonotonicMillis)
        lastSampleReference = checkpoint.lastSampleReference
    }

    fun checkpoint(): SafetyProcessorCheckpoint {
        val reference = requireNotNull(lastSampleReference) { "no safety sample has been processed" }
        return SafetyProcessorCheckpoint(
            thresholdConfigVersion = config.version,
            thresholdConfigFingerprint = configFingerprint,
            lastSampleReference = reference,
            engine = engine.checkpoint(),
            activeEpisodes = activeEpisodes.values.toList(),
        )
    }

    fun reset() {
        engine.reset()
        activeEpisodes.clear()
        lastMonotonicMillis = null
        lastSampleReference = null
    }

    fun process(event: HardwareEvent.SensorSample): SafetySampleProcessingResult {
        val previousMonotonicMillis = lastMonotonicMillis
        val reset = previousMonotonicMillis?.let { event.monotonicMillis <= it } == true
        val terminatedEpisodes = if (reset) activeEpisodes.values.toList() else emptyList()
        if (reset) {
            terminatedEpisodes.forEach { episode ->
                require(event.sampleReference > episode.lastPublishedSampleReference) {
                    "module reset cannot terminate an active safety episode without a strictly newer sample reference"
                }
            }
        }
        if (reset) {
            engine.reset()
            activeEpisodes.clear()
        }
        val evaluation = engine.process(event.toDetectionSample())
        lastMonotonicMillis = event.monotonicMillis
        lastSampleReference = event.sampleReference
        val terminatedAlarms = terminatedEpisodes.map { episode ->
            episode.toTerminationAlarm(
                event = event,
                evaluation = evaluation,
                reasonCode = if (isU32Wrap(requireNotNull(previousMonotonicMillis), event.monotonicMillis)) {
                    "MODULE_MONOTONIC_U32_WRAP"
                } else {
                    "MODULE_MONOTONIC_RESET"
                },
            )
        }
        return SafetySampleProcessingResult(
            evaluation = evaluation,
            alarms = terminatedAlarms + evaluation.decisions.map { decision ->
                EvaluatedSafetyAlarm(
                    event = decision.toHardwareAlarm(event.simulated),
                    evaluation = evaluation,
                    decision = decision,
                )
            },
            engineReset = reset,
        )
    }

    fun process(telemetry: SafetySensorTelemetry): SafetySampleProcessingResult {
        require(
            telemetry.thresholdConfigVersion == config.version &&
                telemetry.thresholdConfigFingerprint == configFingerprint,
        ) {
            "safety sample threshold configuration identity is unknown or different"
        }
        return process(telemetry.toHardwareEvent())
    }

    private fun HardwareEvent.SensorSample.toDetectionSample() = SafetySensorSample(
        sampleReference = sampleReference,
        monotonicMillis = monotonicMillis,
        accelerationXMilliG = accelerationXMilliG,
        accelerationYMilliG = accelerationYMilliG,
        accelerationZMilliG = accelerationZMilliG,
        gyroXMilliDegreesPerSecond = gyroXMilliDegreesPerSecond,
        gyroYMilliDegreesPerSecond = gyroYMilliDegreesPerSecond,
        gyroZMilliDegreesPerSecond = gyroZMilliDegreesPerSecond,
        electricFieldMilliVolts = electricFieldMilliVolts,
        pressurePascals = pressurePascals?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt(),
        temperatureCentiCelsius = temperatureCentiCelsius,
        altitudeMillimetres = altitudeMillimetres,
    )

    private fun SafetySensorTelemetry.toHardwareEvent() = HardwareEvent.SensorSample(
        monotonicMillis = monotonicMillis,
        sampleReference = sampleReference,
        validFlags = validFlags,
        accelerationXMilliG = accelerationXMilliG,
        accelerationYMilliG = accelerationYMilliG,
        accelerationZMilliG = accelerationZMilliG,
        gyroXMilliDegreesPerSecond = gyroXMilliDegreesPerSecond,
        gyroYMilliDegreesPerSecond = gyroYMilliDegreesPerSecond,
        gyroZMilliDegreesPerSecond = gyroZMilliDegreesPerSecond,
        electricFieldMilliVolts = electricFieldMilliVolts,
        pressurePascals = pressurePascals,
        temperatureCentiCelsius = temperatureCentiCelsius,
        altitudeMillimetres = altitudeMillimetres,
        simulated = simulated,
    )

    private fun SafetyDecision.toHardwareAlarm(simulated: Boolean): HardwareEvent.Alarm {
        val key = EpisodeKey(type, sensorFaults)
        val id = if (type in CHECKPOINT_LATCHED_ALARM_TYPES) {
            if (active) {
                val existing = activeEpisodes[key]
                if (existing != null) {
                    require(sampleReference > existing.lastPublishedSampleReference) {
                        "safety episode update requires a strictly newer sample reference"
                    }
                }
                val episode = existing?.copy(
                    lastPublishedSampleReference = sampleReference,
                    severity = severity,
                    localActions = requestedLocalActions,
                ) ?: ActiveSafetyEpisodeCheckpoint(
                    type = type,
                    sensorFaults = sensorFaults,
                    alarmId = composeAlarmId(this),
                    activationSampleReference = sampleReference,
                    lastPublishedSampleReference = sampleReference,
                    severity = severity,
                    localActions = requestedLocalActions,
                    configVersion = configVersion,
                )
                activeEpisodes[key] = episode
                episode.alarmId
            } else {
                activeEpisodes[key]?.let { episode ->
                    require(sampleReference > episode.lastPublishedSampleReference) {
                        "safety episode clear requires a strictly newer sample reference"
                    }
                }
                activeEpisodes.remove(key)?.alarmId ?: composeAlarmId(this)
            }
        } else {
            composeAlarmId(this)
        }
        return HardwareEvent.Alarm(
            monotonicMillis = monotonicMillis,
            alarmType = type.name,
            severity = severity.name,
            simulated = simulated,
            active = active,
            alarmId = id,
            configVersion = configVersion,
            sampleReference = sampleReference,
            localActions = requestedLocalActions,
            sensorFaults = sensorFaults,
            origin = HardwareAlarmOrigin.ANDROID_DETECTION,
        )
    }

    private fun ActiveSafetyEpisodeCheckpoint.toTerminationAlarm(
        event: HardwareEvent.SensorSample,
        evaluation: SafetyEvaluation,
        reasonCode: String,
    ): EvaluatedSafetyAlarm {
        val decision = SafetyDecision(
            type = type,
            severity = SafetySeverity.INFO,
            active = false,
            sampleReference = event.sampleReference,
            monotonicMillis = event.monotonicMillis,
            configVersion = config.version,
            reasonCode = reasonCode,
            measuredValue = 0,
            thresholdValue = 0,
            requestedLocalActions = localActions,
            sensorFaults = sensorFaults,
        )
        return EvaluatedSafetyAlarm(
            event = HardwareEvent.Alarm(
                monotonicMillis = event.monotonicMillis,
                alarmType = type.name,
                severity = SafetySeverity.INFO.name,
                simulated = event.simulated,
                active = false,
                alarmId = alarmId,
                configVersion = config.version,
                sampleReference = event.sampleReference,
                localActions = localActions,
                sensorFaults = sensorFaults,
                origin = HardwareAlarmOrigin.ANDROID_DETECTION,
            ),
            evaluation = evaluation,
            decision = decision,
        )
    }

    private fun composeAlarmId(decision: SafetyDecision): Long =
        ((decision.type.ordinal + 1).toLong() shl TYPE_SHIFT) or
            ((decision.sensorFaults and 0xFFFF).toLong() shl SENSOR_FAULT_SHIFT) or
            (decision.sampleReference and 0xFFFF_FFFFL)

    private companion object {
        const val TYPE_SHIFT = 48
        const val SENSOR_FAULT_SHIFT = 32

        fun isU32Wrap(previous: Long, current: Long): Boolean =
            previous >= 0xF000_0000L && current <= 0x0FFF_FFFFL
    }
}
