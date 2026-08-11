package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.safety.detection.SafetyAlarmType
import com.example.helmet.safety.detection.SafetyDecision
import com.example.helmet.safety.detection.SafetyDetectionEngine
import com.example.helmet.safety.detection.SafetyEvaluation
import com.example.helmet.safety.detection.SafetySensorSample

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

internal class SafetySampleProcessor(
    config: SafetyThresholdConfig,
) {
    private data class EpisodeKey(
        val type: SafetyAlarmType,
        val sensorFaults: Int,
    )

    private val engine = SafetyDetectionEngine(config)
    private val activeAlarmIds = mutableMapOf<EpisodeKey, Long>()
    private var lastMonotonicMillis: Long? = null

    fun process(event: HardwareEvent.SensorSample): SafetySampleProcessingResult {
        val reset = lastMonotonicMillis?.let { event.monotonicMillis <= it } == true
        if (reset) {
            engine.reset()
            activeAlarmIds.clear()
        }
        val evaluation = engine.process(event.toDetectionSample())
        lastMonotonicMillis = event.monotonicMillis
        return SafetySampleProcessingResult(
            evaluation = evaluation,
            alarms = evaluation.decisions.map { decision ->
                EvaluatedSafetyAlarm(
                    event = decision.toHardwareAlarm(event.simulated),
                    evaluation = evaluation,
                    decision = decision,
                )
            },
            engineReset = reset,
        )
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

    private fun SafetyDecision.toHardwareAlarm(simulated: Boolean): HardwareEvent.Alarm {
        val key = EpisodeKey(type, sensorFaults)
        val id = if (type in LATCHED_ALARM_TYPES) {
            if (active) {
                activeAlarmIds.getOrPut(key) { composeAlarmId(this) }
            } else {
                activeAlarmIds.remove(key) ?: composeAlarmId(this)
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

    private fun composeAlarmId(decision: SafetyDecision): Long =
        ((decision.type.ordinal + 1).toLong() shl TYPE_SHIFT) or
            ((decision.sensorFaults and 0xFFFF).toLong() shl SENSOR_FAULT_SHIFT) or
            (decision.sampleReference and 0xFFFF_FFFFL)

    private companion object {
        const val TYPE_SHIFT = 48
        const val SENSOR_FAULT_SHIFT = 32
        val LATCHED_ALARM_TYPES = setOf(
            SafetyAlarmType.NEAR_ELECTRIC,
            SafetyAlarmType.HEIGHT_LIMIT,
            SafetyAlarmType.SENSOR_FAULT,
        )
    }
}
