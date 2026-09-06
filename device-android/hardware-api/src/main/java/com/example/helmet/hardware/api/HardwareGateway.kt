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

enum class HardwareOperationalState(val wireValue: Int) {
    INITIALIZING(0),
    OFFLINE_READY(1),
    IDLE(2),
    CALLING(3),
    IN_CALL(4),
    RECORDING(5),
    SOS(6),
    FAULT(7),
    SHUTTING_DOWN(8),
}

sealed interface HardwareEvent {
    val monotonicMillis: Long

    data class Heartbeat(
        override val monotonicMillis: Long,
    ) : HardwareEvent

    data class ModuleHello(
        override val monotonicMillis: Long,
        val contractVersion: HardwareContractVersion,
        val capabilityMask: Long,
        val firmwareVersion: String,
        val hardwareRevision: Int,
        val bootSessionId: Long,
        val compatible: Boolean,
        val missingCapabilityMask: Long,
    ) : HardwareEvent

    data class Key(
        override val monotonicMillis: Long,
        val input: SimulatedInput,
        val eventId: Long? = null,
        val sequence: Int? = null,
        val acknowledgement: HardwareAcknowledgement? = null,
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
        val acknowledgement: HardwareAcknowledgement? = null,
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
        val sequence: Int? = null,
        val acknowledgement: HardwareAcknowledgement? = null,
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

/**
 * Process-local capability for acknowledging one frame on the provider transport that delivered it.
 * The wire sequence remains public for durable identity and diagnostics; only hardware-api can bind
 * it to a provider epoch.
 */
class HardwareAcknowledgement internal constructor(
    val sequence: Int,
    internal val transportEpoch: ProviderTransportEpoch,
) {
    init {
        require(sequence in 0..0xFFFF)
    }
}

data class HardwareCommand(
    val type: Int,
    val flags: Int,
    val sequence: Int,
    val payload: ByteArray,
)

enum class HardwareCommandOutcome {
    SENT,
    ACKNOWLEDGED,
    REJECTED,
    TIMED_OUT,
    SEND_FAILED,
}

enum class HardwareAcknowledgementResult(val wireValue: Int) {
    SUCCESS(0),
    UNSUPPORTED_VERSION(1),
    UNSUPPORTED_TYPE(2),
    INVALID_LENGTH(3),
    FIELD_OUT_OF_RANGE(4),
}

data class HardwareCommandResult(
    val outcome: HardwareCommandOutcome,
    val type: Int,
    val businessSequence: Int,
    val wireSequence: Int,
    val resultCode: Int? = null,
    val detailSize: Int = 0,
) {
    val accepted: Boolean
        get() = outcome == HardwareCommandOutcome.SENT || outcome == HardwareCommandOutcome.ACKNOWLEDGED
}

class HardwareCommandException(val result: HardwareCommandResult) : IllegalStateException(
    "hardware command failed: outcome=${result.outcome} type=${result.type} " +
        "wireSequence=${result.wireSequence} resultCode=${result.resultCode}",
)

data class HardwareStatus(
    val connected: Boolean = false,
    val simulated: Boolean = true,
    val lastHeartbeatMillis: Long? = null,
    val linkState: String = "DISCONNECTED",
    val lastError: String? = null,
    val eventQueueOverflowCount: Long = 0,
    val moduleSessionGeneration: Long = 0,
    val compatibility: HardwareCompatibility = HardwareCompatibility.AWAITING_HELLO,
    val moduleContractVersion: String? = null,
    val moduleFirmwareVersion: String? = null,
    val moduleCapabilityMask: Long = 0,
    val missingCapabilityMask: Long = 0,
    val moduleHardwareRevision: Int? = null,
    val moduleBootSessionId: Long? = null,
)

enum class HardwareCompatibility {
    AWAITING_HELLO,
    COMPATIBLE,
    CONTRACT_VERSION_MISMATCH,
    REQUIRED_CAPABILITIES_MISSING,
    NOT_APPLICABLE,
}

internal fun HardwareStatus.afterHeartbeat(monotonicMillis: Long): HardwareStatus = copy(
    connected = compatibility == HardwareCompatibility.COMPATIBLE,
    lastHeartbeatMillis = monotonicMillis,
    linkState = if (compatibility == HardwareCompatibility.COMPATIBLE) "CONNECTED" else "AWAITING_COMPATIBILITY",
    lastError = lastError.takeUnless { compatibility == HardwareCompatibility.COMPATIBLE },
)

interface HardwareGateway {
    val status: StateFlow<HardwareStatus>
    val events: Flow<HardwareEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun send(command: HardwareCommand)
    suspend fun sendForResult(command: HardwareCommand): HardwareCommandResult {
        send(command)
        return HardwareCommandResult(
            outcome = HardwareCommandOutcome.SENT,
            type = command.type,
            businessSequence = command.sequence,
            wireSequence = command.sequence,
        )
    }
    suspend fun acknowledge(acknowledgement: HardwareAcknowledgement, resultCode: Int) = Unit
    fun updateOperationalState(state: HardwareOperationalState) = Unit
}
