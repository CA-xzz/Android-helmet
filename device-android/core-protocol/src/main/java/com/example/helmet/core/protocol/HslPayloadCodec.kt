package com.example.helmet.core.protocol

data class HslAck(
    val acknowledgedSequence: Int,
    val resultCode: Int,
    val detail: ByteArray = byteArrayOf(),
)

data class HslHeartbeat(
    val uptimeMillis: Long,
    val operationalState: Int,
    val protocolVersion: Int,
    val errorCount: Int,
)

data class HslKeyEvent(
    val eventId: Long,
    val key: Int,
    val action: Int,
    val durationMillis: Int,
    val monotonicMillis: Long,
)

data class HslAlarmEvent(
    val alarmId: Long,
    val alarmType: Int,
    val severity: Int,
    val state: Int,
    val configVersion: Int,
    val monotonicMillis: Long,
    val sampleReference: Long,
    val localActions: Int,
    val sensorFaults: Int,
    val embeddedSample: HslSensorSample? = null,
)

data class HslSensorSample(
    val sampleReference: Long,
    val monotonicMillis: Long,
    val validFlags: Int,
    val accelerationXMilliG: Int,
    val accelerationYMilliG: Int,
    val accelerationZMilliG: Int,
    val gyroXMilliDegreesPerSecond: Int,
    val gyroYMilliDegreesPerSecond: Int,
    val gyroZMilliDegreesPerSecond: Int,
    val electricFieldMilliVolts: Int,
    val pressurePascals: Long,
    val temperatureCentiCelsius: Int,
    val altitudeMillimetres: Int,
)

object HslPayloadCodec {
    fun encodeHeartbeat(heartbeat: HslHeartbeat): ByteArray {
        require(heartbeat.uptimeMillis in 0..0xFFFF_FFFFL)
        require(heartbeat.operationalState in 0..0xFF)
        require(heartbeat.protocolVersion in 0..0xFF)
        require(heartbeat.errorCount in 0..0xFFFF)
        return ByteArray(HEARTBEAT_SIZE).also { output ->
            output.putU32Le(0, heartbeat.uptimeMillis)
            output[4] = heartbeat.operationalState.toByte()
            output[5] = heartbeat.protocolVersion.toByte()
            output.putU16Le(6, heartbeat.errorCount)
        }
    }

    fun decodeHeartbeat(payload: ByteArray): HslHeartbeat {
        require(payload.size == HEARTBEAT_SIZE) { "HEARTBEAT payload length must be $HEARTBEAT_SIZE" }
        return HslHeartbeat(
            uptimeMillis = payload.u32Le(0),
            operationalState = payload[4].toInt() and 0xFF,
            protocolVersion = payload[5].toInt() and 0xFF,
            errorCount = payload.u16Le(6),
        )
    }

    fun encodeAck(ack: HslAck): ByteArray {
        require(ack.acknowledgedSequence in 0..0xFFFF)
        require(ack.resultCode in 0..0xFF)
        require(ack.detail.size <= HslFrameCodec.MAX_PAYLOAD_SIZE - 3)
        return ByteArray(3 + ack.detail.size).also { output ->
            output.putU16Le(0, ack.acknowledgedSequence)
            output[2] = ack.resultCode.toByte()
            ack.detail.copyInto(output, 3)
        }
    }

    fun decodeAck(payload: ByteArray): HslAck {
        require(payload.size >= 3) { "ACK payload is too short" }
        return HslAck(
            acknowledgedSequence = payload.u16Le(0),
            resultCode = payload[2].toInt() and 0xFF,
            detail = payload.copyOfRange(3, payload.size),
        )
    }

    fun decodeKeyEvent(payload: ByteArray): HslKeyEvent {
        require(payload.size == 12) { "KEY_EVENT payload length must be 12" }
        return HslKeyEvent(
            eventId = payload.u32Le(0),
            key = payload[4].toInt() and 0xFF,
            action = payload[5].toInt() and 0xFF,
            durationMillis = payload.u16Le(6),
            monotonicMillis = payload.u32Le(8),
        )
    }

    fun decodeAlarmEvent(payload: ByteArray): HslAlarmEvent {
        require(payload.size == ALARM_EVENT_SIZE || payload.size == ALARM_EVENT_WITH_SAMPLE_SIZE) {
            "ALARM_EVENT payload length must be $ALARM_EVENT_SIZE or $ALARM_EVENT_WITH_SAMPLE_SIZE"
        }
        val alarmId = payload.u32Le(0)
        val alarmType = payload[4].toInt() and 0xFF
        val severity = payload[5].toInt() and 0xFF
        val state = payload[6].toInt() and 0xFF
        val configVersion = payload.u16Le(7)
        val monotonicMillis = payload.u32Le(9)
        val sampleReference = payload.u32Le(13)
        val localActions = payload[17].toInt() and 0xFF
        val sensorFaults = payload.u16Le(18)
        require(alarmId != 0L) { "alarmId must be non-zero" }
        require(alarmType in 1..6) { "invalid alarmType" }
        require(severity in 0..4) { "invalid alarm severity" }
        require(state in 0..1) { "invalid alarm state" }
        require(configVersion != 0) { "configVersion must be non-zero" }
        require(sampleReference != 0L) { "sampleReference must be non-zero" }
        require(localActions and 0xF8 == 0) { "invalid localActions" }
        require(sensorFaults and 0xFFF8 == 0) { "invalid sensorFaults" }
        val embeddedSample = if (payload.size == ALARM_EVENT_WITH_SAMPLE_SIZE) {
            decodeSensorSample(payload.copyOfRange(ALARM_EVENT_SIZE, ALARM_EVENT_WITH_SAMPLE_SIZE)).also { sample ->
                require(sample.sampleReference == sampleReference) {
                    "embedded sample reference does not match ALARM_EVENT"
                }
                require(sample.monotonicMillis == monotonicMillis) {
                    "embedded sample monotonic time does not match ALARM_EVENT"
                }
            }
        } else {
            null
        }
        return HslAlarmEvent(
            alarmId = alarmId,
            alarmType = alarmType,
            severity = severity,
            state = state,
            configVersion = configVersion,
            monotonicMillis = monotonicMillis,
            sampleReference = sampleReference,
            localActions = localActions,
            sensorFaults = sensorFaults,
            embeddedSample = embeddedSample,
        )
    }

    fun encodeSensorSample(sample: HslSensorSample): ByteArray {
        require(sample.sampleReference in 0..0xFFFF_FFFFL)
        require(sample.monotonicMillis in 0..0xFFFF_FFFFL)
        require(sample.validFlags in 0..0xFFFF)
        require(sample.accelerationXMilliG in Short.MIN_VALUE..Short.MAX_VALUE)
        require(sample.accelerationYMilliG in Short.MIN_VALUE..Short.MAX_VALUE)
        require(sample.accelerationZMilliG in Short.MIN_VALUE..Short.MAX_VALUE)
        require(sample.electricFieldMilliVolts in 0..0xFFFF)
        require(sample.pressurePascals in 0..0xFFFF_FFFFL)
        require(sample.temperatureCentiCelsius in Short.MIN_VALUE..Short.MAX_VALUE)
        return ByteArray(SENSOR_SAMPLE_SIZE).also { output ->
            output.putU32Le(0, sample.sampleReference)
            output.putU32Le(4, sample.monotonicMillis)
            output.putU16Le(8, sample.validFlags)
            output.putI16Le(10, sample.accelerationXMilliG)
            output.putI16Le(12, sample.accelerationYMilliG)
            output.putI16Le(14, sample.accelerationZMilliG)
            output.putI32Le(16, sample.gyroXMilliDegreesPerSecond)
            output.putI32Le(20, sample.gyroYMilliDegreesPerSecond)
            output.putI32Le(24, sample.gyroZMilliDegreesPerSecond)
            output.putU16Le(28, sample.electricFieldMilliVolts)
            output.putU32Le(30, sample.pressurePascals)
            output.putI16Le(34, sample.temperatureCentiCelsius)
            output.putI32Le(36, sample.altitudeMillimetres)
        }
    }

    fun decodeSensorSample(payload: ByteArray): HslSensorSample {
        require(payload.size == SENSOR_SAMPLE_SIZE) { "SENSOR_SAMPLE payload length must be $SENSOR_SAMPLE_SIZE" }
        return HslSensorSample(
            sampleReference = payload.u32Le(0),
            monotonicMillis = payload.u32Le(4),
            validFlags = payload.u16Le(8),
            accelerationXMilliG = payload.i16Le(10),
            accelerationYMilliG = payload.i16Le(12),
            accelerationZMilliG = payload.i16Le(14),
            gyroXMilliDegreesPerSecond = payload.i32Le(16),
            gyroYMilliDegreesPerSecond = payload.i32Le(20),
            gyroZMilliDegreesPerSecond = payload.i32Le(24),
            electricFieldMilliVolts = payload.u16Le(28),
            pressurePascals = payload.u32Le(30),
            temperatureCentiCelsius = payload.i16Le(34),
            altitudeMillimetres = payload.i32Le(36),
        )
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.putI16Le(offset: Int, value: Int) = putU16Le(offset, value and 0xFFFF)

    private fun ByteArray.putI32Le(offset: Int, value: Int) = putU32Le(offset, value.toLong() and 0xFFFF_FFFFL)

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.i16Le(offset: Int): Int = u16Le(offset).toShort().toInt()

    private fun ByteArray.i32Le(offset: Int): Int = u32Le(offset).toInt()

    private const val ALARM_EVENT_SIZE = 20
    private const val SENSOR_SAMPLE_SIZE = 40
    private const val ALARM_EVENT_WITH_SAMPLE_SIZE = ALARM_EVENT_SIZE + SENSOR_SAMPLE_SIZE
    private const val HEARTBEAT_SIZE = 8
}
