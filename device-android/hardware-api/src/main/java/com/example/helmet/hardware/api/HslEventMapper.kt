package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec

internal object HslEventMapper {
    fun map(
        frame: HslFrame,
        acknowledgement: HardwareAcknowledgement? = null,
    ): HardwareEvent? {
        require(
            acknowledgement == null ||
                (acknowledgement.sequence == frame.sequence && frame.flags and HslFlags.ACK_REQUIRED != 0),
        )
        return when (frame.type) {
            HslMessageType.KEY_EVENT -> mapKey(frame, acknowledgement)
            HslMessageType.SENSOR_SAMPLE -> mapSensorSample(frame, acknowledgement)
            HslMessageType.ALARM_EVENT -> mapAlarm(frame, acknowledgement)
            else -> null
        }
    }

    private fun mapKey(
        frame: HslFrame,
        acknowledgement: HardwareAcknowledgement?,
    ): HardwareEvent.Key? {
        val event = runCatching { HslPayloadCodec.decodeKeyEvent(frame.payload) }.getOrNull() ?: return null
        val input = when (event.key) {
            KEY_CAMERA -> when (event.action) {
                ACTION_SHORT_PRESS -> SimulatedInput.PHOTO_SHORT
                ACTION_LONG_PRESS -> SimulatedInput.RECORD_LONG
                ACTION_DOUBLE_CLICK -> SimulatedInput.PHOTO_SHORT
                else -> null
            }
            KEY_CALL -> event.action.asCompletedPress()?.let { SimulatedInput.CALL }
            KEY_SOS -> event.action.asCompletedPress()?.let { SimulatedInput.SOS }
            KEY_VOLUME_UP -> event.action.asCompletedPress()?.let { SimulatedInput.VOLUME_UP }
            KEY_VOLUME_DOWN -> event.action.asCompletedPress()?.let { SimulatedInput.VOLUME_DOWN }
            else -> null
        } ?: return null
        return HardwareEvent.Key(
            monotonicMillis = event.monotonicMillis,
            input = input,
            eventId = event.eventId,
            sequence = frame.sequence.takeIf { frame.flags and HslFlags.ACK_REQUIRED != 0 },
            acknowledgement = acknowledgement,
        )
    }

    private fun mapAlarm(
        frame: HslFrame,
        acknowledgement: HardwareAcknowledgement?,
    ): HardwareEvent.Alarm? {
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
            ALARM_INACTIVITY -> "INACTIVITY"
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
            acknowledgement = acknowledgement,
        )
    }

    private fun mapSensorSample(
        frame: HslFrame,
        acknowledgement: HardwareAcknowledgement?,
    ): HardwareEvent.SensorSample? {
        val sample = runCatching { HslPayloadCodec.decodeSensorSample(frame.payload) }.getOrNull() ?: return null
        return sample.toHardwareEvent(
            sequence = frame.sequence.takeIf { frame.flags and HslFlags.ACK_REQUIRED != 0 },
            acknowledgement = acknowledgement,
        )
    }

    private fun com.example.helmet.core.protocol.HslSensorSample.toHardwareEvent(
        sequence: Int? = null,
        acknowledgement: HardwareAcknowledgement? = null,
    ): HardwareEvent.SensorSample {
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
            pressurePascals = sample.pressurePascals.takeIf { valid(VALID_PRESSURE) },
            temperatureCentiCelsius = sample.temperatureCentiCelsius.takeIf { valid(VALID_TEMPERATURE) },
            altitudeMillimetres = sample.altitudeMillimetres.takeIf { valid(VALID_ALTITUDE) },
            simulated = false,
            sequence = sequence,
            acknowledgement = acknowledgement,
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
    private const val ALARM_INACTIVITY = 7
    private const val VALID_IMU = 0x0001
    private const val VALID_ELECTRIC = 0x0002
    private const val VALID_ALTITUDE = 0x0004
    private const val VALID_TEMPERATURE = 0x0008
    private const val VALID_PRESSURE = 0x0010
}
