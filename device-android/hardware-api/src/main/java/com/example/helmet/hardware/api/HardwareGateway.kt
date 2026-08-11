package com.example.helmet.hardware.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SimulatedInput {
    PHOTO_SHORT,
    RECORD_LONG,
    CALL,
    SOS,
    VOLUME_UP,
    VOLUME_DOWN,
    FALL,
    NEAR_ELECTRIC,
    HEIGHT_LIMIT,
}

enum class HardwareAlarmOrigin {
    EXTERNAL_MODULE,
    ANDROID_DETECTION,
    SIMULATOR,
}

sealed interface HardwareEvent {
    val monotonicMillis: Long

    data class Heartbeat(
        override val monotonicMillis: Long,
    ) : HardwareEvent

    data class Key(
        override val monotonicMillis: Long,
        val input: SimulatedInput,
    ) : HardwareEvent

    data class Alarm(
        override val monotonicMillis: Long,
        val alarmType: String,
        val severity: String,
        val simulated: Boolean,
        val active: Boolean = true,
        val alarmId: Long? = null,
        val configVersion: Int? = null,
        val sampleReference: Long? = null,
        val localActions: Int = 0,
        val sensorFaults: Int = 0,
        val sequence: Int? = null,
        val embeddedSample: SensorSample? = null,
        val origin: HardwareAlarmOrigin = HardwareAlarmOrigin.EXTERNAL_MODULE,
    ) : HardwareEvent

    data class SensorSample(
        override val monotonicMillis: Long,
        val sampleReference: Long,
        val validFlags: Int,
        val accelerationXMilliG: Int?,
        val accelerationYMilliG: Int?,
        val accelerationZMilliG: Int?,
        val gyroXMilliDegreesPerSecond: Int?,
        val gyroYMilliDegreesPerSecond: Int?,
        val gyroZMilliDegreesPerSecond: Int?,
        val electricFieldMilliVolts: Int?,
        val pressurePascals: Long?,
        val temperatureCentiCelsius: Int?,
        val altitudeMillimetres: Int?,
        val simulated: Boolean,
    ) : HardwareEvent

    data class ProtocolFrame(
        override val monotonicMillis: Long,
        val version: Int,
        val flags: Int,
        val type: Int,
        val sequence: Int,
        val payload: ByteArray,
    ) : HardwareEvent
}

data class HardwareCommand(
    val type: Int,
    val flags: Int,
    val sequence: Int,
    val payload: ByteArray,
)

data class HardwareStatus(
    val connected: Boolean = false,
    val simulated: Boolean = true,
    val lastHeartbeatMillis: Long? = null,
    val linkState: String = "DISCONNECTED",
    val lastError: String? = null,
)

internal fun HardwareStatus.afterHeartbeat(monotonicMillis: Long): HardwareStatus = copy(
    connected = true,
    lastHeartbeatMillis = monotonicMillis,
    linkState = "CONNECTED",
    lastError = null,
)

interface HardwareGateway {
    val status: StateFlow<HardwareStatus>
    val events: Flow<HardwareEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun send(command: HardwareCommand)
    suspend fun acknowledge(acknowledgedSequence: Int, resultCode: Int) = Unit
}
