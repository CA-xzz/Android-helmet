package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec

internal object HslEventMapper {
    fun map(frame: HslFrame): HardwareEvent? = when (frame.type) {
        HslMessageType.KEY_EVENT -> mapKey(frame)
        HslMessageType.SENSOR_SAMPLE -> mapSensorSample(frame)
        HslMessageType.ALARM_EVENT -> mapAlarm(frame)
        else -> null
    }

    private fun mapKey(frame: HslFrame): HardwareEvent.Key? {
        val event = runCatching { HslPayloadCodec.decodeKeyEvent(frame.payload) }.getOrNull() ?: return null
        val input = when (event.key) {
            KEY_CAMERA -> when (event.action) {
                ACTION_SHORT_PRESS -> SimulatedInput.PHOTO_SHORT
                ACTION_LONG_PRESS -> SimulatedInput.RECORD_LONG
                else -> null
            }
            KEY_CALL -> event.action.asCompletedPress()?.let { SimulatedInput.CALL }
            KEY_SOS -> event.action.asCompletedPress()?.let { SimulatedInput.SOS }
            KEY_VOLUME_UP -> event.action.asCompletedPress()?.let { SimulatedInput.VOLUME_UP }
            KEY_VOLUME_DOWN -> event.action.asCompletedPress()?.let { SimulatedInput.VOLUME_DOWN }
            else -> null
        } ?: return null
        return HardwareEvent.Key(event.monotonicMillis, input)
    }

    private fun mapAlarm(frame: HslFrame): HardwareEvent.Alarm? {
        if (frame.flags and com.example.helmet.core.protocol.HslFlags.ACK_REQUIRED == 0) return null
        val event = runCatching { HslPayloadCodec.decodeAlarmEvent(frame.payload) }.getOrNull() ?: return null
        if (event.state !in setOf(ALARM_STATE_CLEARED, ALARM_STATE_ACTIVE)) return null
        val alarmType = when (event.alarmType) {
            ALARM_FALL -> "FALL"
            ALARM_NEAR_ELECTRIC -> "NEAR_ELECTRIC"
            ALARM_HEIGHT_LIMIT -> "HEIGHT_LIMIT"
            ALARM_IMPACT -> "IMPACT"
            ALARM_VIOLENT_SHAKE -> "VIOLENT_SHAKE"
            ALARM_SENSOR_FAULT -> "SENSOR_FAULT"
            else -> "UNKNOWN_${event.alarmType}"
        }
        val severity = when (event.severity) {
            0 -> "INFO"
            1 -> "LOW"
            2 -> "MEDIUM"
            3 -> "HIGH"
            else -> "CRITICAL"
        }
        return HardwareEvent.Alarm(
            monotonicMillis = event.monotonicMillis,
            alarmType = alarmType,
            severity = severity,
            simulated = false,
            active = event.state == ALARM_STATE_ACTIVE,
            alarmId = event.alarmId,
            configVersion = event.configVersion,
            sampleReference = event.sampleReference,
            localActions = event.localActions,
            sensorFaults = event.sensorFaults,
            sequence = frame.sequence,
            embeddedSample = event.embeddedSample?.toHardwareEvent(),
        )
    }

    private fun mapSensorSample(frame: HslFrame): HardwareEvent.SensorSample? {
        val sample = runCatching { HslPayloadCodec.decodeSensorSample(frame.payload) }.getOrNull() ?: return null
        return sample.toHardwareEvent()
    }

    private fun com.example.helmet.core.protocol.HslSensorSample.toHardwareEvent(): HardwareEvent.SensorSample {
        val sample = this
        fun valid(bit: Int): Boolean = sample.validFlags and bit != 0
        return HardwareEvent.SensorSample(
            monotonicMillis = sample.monotonicMillis,
            sampleReference = sample.sampleReference,
            validFlags = sample.validFlags,
            accelerationXMilliG = sample.accelerationXMilliG.takeIf { valid(VALID_IMU) },
            accelerationYMilliG = sample.accelerationYMilliG.takeIf { valid(VALID_IMU) },
            accelerationZMilliG = sample.accelerationZMilliG.takeIf { valid(VALID_IMU) },
            gyroXMilliDegreesPerSecond = sample.gyroXMilliDegreesPerSecond.takeIf { valid(VALID_IMU) },
            gyroYMilliDegreesPerSecond = sample.gyroYMilliDegreesPerSecond.takeIf { valid(VALID_IMU) },
            gyroZMilliDegreesPerSecond = sample.gyroZMilliDegreesPerSecond.takeIf { valid(VALID_IMU) },
            electricFieldMilliVolts = sample.electricFieldMilliVolts.takeIf { valid(VALID_ELECTRIC) },
            pressurePascals = sample.pressurePascals.takeIf { valid(VALID_HEIGHT) },
            temperatureCentiCelsius = sample.temperatureCentiCelsius.takeIf { valid(VALID_TEMPERATURE) },
            altitudeMillimetres = sample.altitudeMillimetres.takeIf { valid(VALID_HEIGHT) },
            simulated = false,
        )
    }

    private fun Int.asCompletedPress(): Unit? =
        if (this == ACTION_SHORT_PRESS || this == ACTION_LONG_PRESS || this == ACTION_DOUBLE_CLICK) Unit else null

    private const val KEY_CAMERA = 1
    private const val KEY_CALL = 2
    private const val KEY_SOS = 3
    private const val KEY_VOLUME_UP = 4
    private const val KEY_VOLUME_DOWN = 5
    private const val ACTION_SHORT_PRESS = 3
    private const val ACTION_LONG_PRESS = 4
    private const val ACTION_DOUBLE_CLICK = 5
    private const val ALARM_STATE_ACTIVE = 1
    private const val ALARM_STATE_CLEARED = 0
    private const val ALARM_FALL = 1
    private const val ALARM_NEAR_ELECTRIC = 2
    private const val ALARM_HEIGHT_LIMIT = 3
    private const val ALARM_IMPACT = 4
    private const val ALARM_VIOLENT_SHAKE = 5
    private const val ALARM_SENSOR_FAULT = 6
    private const val VALID_IMU = 0x0001
    private const val VALID_ELECTRIC = 0x0002
    private const val VALID_HEIGHT = 0x0004
    private const val VALID_TEMPERATURE = 0x0008
}
