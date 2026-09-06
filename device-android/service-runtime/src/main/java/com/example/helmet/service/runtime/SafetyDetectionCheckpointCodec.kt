package com.example.helmet.service.runtime

import com.example.helmet.data.local.SafetyDetectionCheckpointRecord
import com.example.helmet.safety.detection.ElectricFieldDetectorCheckpoint
import com.example.helmet.safety.detection.ElectricFieldLevel
import com.example.helmet.safety.detection.HeightDetectorCheckpoint
import com.example.helmet.safety.detection.HeightInputSource
import com.example.helmet.safety.detection.MotionDetectorCheckpoint
import com.example.helmet.safety.detection.SafetyAlarmType
import com.example.helmet.safety.detection.SafetyDetectionCheckpoint
import com.example.helmet.safety.detection.SafetyDetectionEngine
import com.example.helmet.safety.detection.SafetySeverity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

internal data class ActiveSafetyEpisodeCheckpoint(
    val type: SafetyAlarmType,
    val sensorFaults: Int,
    val alarmId: Long,
    val activationSampleReference: Long,
    val lastPublishedSampleReference: Long,
    val severity: SafetySeverity,
    val localActions: Int,
    val configVersion: Int,
) {
    init {
        require(type in CHECKPOINT_LATCHED_ALARM_TYPES)
        require(sensorFaults in 0..0xFFFF)
        require(alarmId > 0)
        require(activationSampleReference in 0..0xFFFF_FFFFL)
        require(lastPublishedSampleReference in activationSampleReference..0xFFFF_FFFFL)
        require(localActions in 0..0xFF)
        require(configVersion in 1..0xFFFF)
    }
}

internal data class SafetyProcessorCheckpoint(
    val thresholdConfigVersion: Int,
    val thresholdConfigFingerprint: String,
    val lastSampleReference: Long,
    val engine: SafetyDetectionCheckpoint,
    val activeEpisodes: List<ActiveSafetyEpisodeCheckpoint>,
) {
    init {
        require(thresholdConfigVersion in 1..0xFFFF)
        require(thresholdConfigFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastSampleReference in 0..0xFFFF_FFFFL)
        require(engine.algorithmVersion == SafetyDetectionEngine.ALGORITHM_VERSION)
        require(engine.thresholdConfigVersion == thresholdConfigVersion)
        require(engine.thresholdConfigFingerprint == thresholdConfigFingerprint)
        require(engine.lastMonotonicMillis != null)
        require(activeEpisodes.size <= MAX_ACTIVE_EPISODES)
        require(activeEpisodes.distinctBy { it.type to it.sensorFaults }.size == activeEpisodes.size)
        require(activeEpisodes.all { it.configVersion == thresholdConfigVersion })
    }

    fun toRecord(deviceId: String, updatedAtEpochMillis: Long): SafetyDetectionCheckpointRecord =
        SafetyDetectionCheckpointRecord(
            deviceId = deviceId,
            schemaVersion = CHECKPOINT_SCHEMA_VERSION,
            algorithmVersion = engine.algorithmVersion,
            thresholdConfigVersion = thresholdConfigVersion,
            thresholdConfigFingerprint = thresholdConfigFingerprint,
            lastSampleReference = lastSampleReference,
            lastMonotonicMillis = requireNotNull(engine.lastMonotonicMillis),
            payload = encode(),
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun encode(): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(CHECKPOINT_MAGIC)
                output.writeInt(CHECKPOINT_SCHEMA_VERSION)
                output.writeInt(engine.algorithmVersion)
                output.writeInt(thresholdConfigVersion)
                output.writeUTF(thresholdConfigFingerprint)
                output.writeLong(lastSampleReference)
                output.writeNullableLong(engine.lastMonotonicMillis)
                engine.motion.writeTo(output)
                engine.electric.writeTo(output)
                engine.height.writeTo(output)
                output.writeInt(activeEpisodes.size)
                activeEpisodes.sortedWith(compareBy({ it.type.ordinal }, { it.sensorFaults })).forEach { episode ->
                    output.writeInt(episode.type.ordinal)
                    output.writeInt(episode.sensorFaults)
                    output.writeLong(episode.alarmId)
                    output.writeLong(episode.activationSampleReference)
                    output.writeLong(episode.lastPublishedSampleReference)
                    output.writeInt(episode.severity.ordinal)
                    output.writeInt(episode.localActions)
                    output.writeInt(episode.configVersion)
                }
            }
            buffer.toByteArray()
        }
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun decode(record: SafetyDetectionCheckpointRecord): SafetyProcessorCheckpoint {
            require(record.schemaVersion in MIN_SUPPORTED_CHECKPOINT_SCHEMA_VERSION..CHECKPOINT_SCHEMA_VERSION) {
                "unsupported checkpoint schema version"
            }
            require(record.algorithmVersion == SafetyDetectionEngine.ALGORITHM_VERSION) {
                "unsupported detection algorithm version"
            }
            val bytes = runCatching { Base64.getDecoder().decode(record.payload) }
                .getOrElse { throw IllegalArgumentException("checkpoint payload is not base64", it) }
            require(bytes.size in 1..MAX_DECODED_CHECKPOINT_BYTES) { "checkpoint payload size is invalid" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == CHECKPOINT_MAGIC) { "checkpoint magic is invalid" }
                require(input.readInt() == record.schemaVersion) { "checkpoint schema metadata mismatch" }
                val algorithmVersion = input.readInt()
                require(algorithmVersion == record.algorithmVersion) { "checkpoint algorithm metadata mismatch" }
                val thresholdConfigVersion = input.readInt()
                require(thresholdConfigVersion == record.thresholdConfigVersion) {
                    "checkpoint threshold metadata mismatch"
                }
                val thresholdConfigFingerprint = input.readUTF()
                require(thresholdConfigFingerprint == record.thresholdConfigFingerprint) {
                    "checkpoint threshold fingerprint metadata mismatch"
                }
                val lastSampleReference = input.readLong()
                require(lastSampleReference == record.lastSampleReference) { "checkpoint sample metadata mismatch" }
                val lastMonotonicMillis = input.readNullableLong()
                require(lastMonotonicMillis == record.lastMonotonicMillis) { "checkpoint clock metadata mismatch" }
                val motion = input.readMotionCheckpoint(record.schemaVersion)
                val electric = input.readElectricCheckpoint()
                val height = input.readHeightCheckpoint()
                val episodeCount = input.readInt()
                require(episodeCount in 0..MAX_ACTIVE_EPISODES) { "checkpoint episode count is invalid" }
                val episodes = List(episodeCount) {
                    ActiveSafetyEpisodeCheckpoint(
                        type = enumAt<SafetyAlarmType>(input.readInt()),
                        sensorFaults = input.readInt(),
                        alarmId = input.readLong(),
                        activationSampleReference = input.readLong(),
                        lastPublishedSampleReference = input.readLong(),
                        severity = enumAt<SafetySeverity>(input.readInt()),
                        localActions = input.readInt(),
                        configVersion = input.readInt(),
                    )
                }
                require(input.available() == 0) { "checkpoint payload has trailing bytes" }
                SafetyProcessorCheckpoint(
                    thresholdConfigVersion = thresholdConfigVersion,
                    thresholdConfigFingerprint = thresholdConfigFingerprint,
                    lastSampleReference = lastSampleReference,
                    engine = SafetyDetectionCheckpoint(
                        algorithmVersion = algorithmVersion,
                        thresholdConfigVersion = thresholdConfigVersion,
                        thresholdConfigFingerprint = thresholdConfigFingerprint,
                        lastMonotonicMillis = lastMonotonicMillis,
                        motion = motion,
                        electric = electric,
                        height = height,
                    ),
                    activeEpisodes = episodes,
                )
            }
        }
    }
}

private fun MotionDetectorCheckpoint.writeTo(output: DataOutputStream) {
    output.writeNullableLong(freeFallStartMillis)
    output.writeNullableLong(fallArmedUntilMillis)
    output.writeLong(cooldownUntilMillis)
    output.writeBoolean(impactArmed)
    output.writeNullableLong(shakeWindowStartMillis)
    output.writeInt(shakeDirection)
    output.writeInt(shakeDirectionChanges)
    output.writeBoolean(shakeArmed)
    output.writeNullableLong(inactivityStartMillis)
    output.writeBoolean(inactivityActive)
}

private fun DataInputStream.readMotionCheckpoint(schemaVersion: Int) = MotionDetectorCheckpoint(
    freeFallStartMillis = readNullableLong(),
    fallArmedUntilMillis = readNullableLong(),
    cooldownUntilMillis = readLong(),
    impactArmed = if (schemaVersion >= 5) readBoolean() else true,
    shakeWindowStartMillis = readNullableLong(),
    shakeDirection = readInt(),
    shakeDirectionChanges = readInt(),
    shakeArmed = if (schemaVersion >= 5) readBoolean() else true,
    inactivityStartMillis = if (schemaVersion >= 4) readNullableLong() else null,
    inactivityActive = if (schemaVersion >= 4) readBoolean() else false,
)

private fun ElectricFieldDetectorCheckpoint.writeTo(output: DataOutputStream) {
    output.writeInt(calibrationCount)
    output.writeLong(calibrationSum)
    output.writeInt(calibrationMinimum)
    output.writeInt(calibrationMaximum)
    output.writeNullableInt(baselineMilliVolts)
    output.writeInt(level.ordinal)
    output.writeNullableEnum(candidateLevel)
    output.writeInt(candidateCount)
    output.writeLong(exposureMilliVoltMillis)
    output.writeNullableLong(lastMillis)
    output.writeBoolean(faultActive)
}

private fun DataInputStream.readElectricCheckpoint() = ElectricFieldDetectorCheckpoint(
    calibrationCount = readInt(),
    calibrationSum = readLong(),
    calibrationMinimum = readInt(),
    calibrationMaximum = readInt(),
    baselineMilliVolts = readNullableInt(),
    level = enumAt(readInt()),
    candidateLevel = readNullableEnum<ElectricFieldLevel>(),
    candidateCount = readInt(),
    exposureMilliVoltMillis = readLong(),
    lastMillis = readNullableLong(),
    faultActive = readBoolean(),
)

private fun HeightDetectorCheckpoint.writeTo(output: DataOutputStream) {
    output.writeInt(calibrationCount)
    output.writeLong(calibrationSum)
    output.writeInt(calibrationMinimum)
    output.writeInt(calibrationMaximum)
    output.writeNullableDouble(baselineMillimetres)
    output.writeBoolean(active)
    output.writeNullableBoolean(candidateActive)
    output.writeInt(candidateCount)
    output.writeNullableLong(candidateStartMillis)
    output.writeNullableEnum(inputSource)
    output.writeNullableInt(lastHeightMillimetres)
    output.writeNullableLong(lastHeightMillis)
    output.writeNullableLong(driftStableSinceMillis)
    output.writeBoolean(faultActive)
}

private fun DataInputStream.readHeightCheckpoint() = HeightDetectorCheckpoint(
    calibrationCount = readInt(),
    calibrationSum = readLong(),
    calibrationMinimum = readInt(),
    calibrationMaximum = readInt(),
    baselineMillimetres = readNullableDouble(),
    active = readBoolean(),
    candidateActive = readNullableBoolean(),
    candidateCount = readInt(),
    candidateStartMillis = readNullableLong(),
    inputSource = readNullableEnum<HeightInputSource>(),
    lastHeightMillimetres = readNullableInt(),
    lastHeightMillis = readNullableLong(),
    driftStableSinceMillis = readNullableLong(),
    faultActive = readBoolean(),
)

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    value?.let(::writeInt)
}

private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

private fun DataOutputStream.writeNullableDouble(value: Double?) {
    writeBoolean(value != null)
    value?.let(::writeDouble)
}

private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

private fun DataOutputStream.writeNullableBoolean(value: Boolean?) {
    writeByte(
        when (value) {
            null -> -1
            false -> 0
            true -> 1
        },
    )
}

private fun DataInputStream.readNullableBoolean(): Boolean? = when (val encoded = readByte().toInt()) {
    -1 -> null
    0 -> false
    1 -> true
    else -> error("invalid nullable boolean $encoded")
}

private fun <T : Enum<T>> DataOutputStream.writeNullableEnum(value: T?) {
    writeInt(value?.ordinal ?: -1)
}

private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
    readInt().takeUnless { it == -1 }?.let { ordinal -> enumAt<T>(ordinal) }

private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
    enumValues<T>().getOrNull(ordinal) ?: error("invalid ${T::class.java.simpleName} ordinal $ordinal")

internal const val CHECKPOINT_SCHEMA_VERSION = 5
private const val MIN_SUPPORTED_CHECKPOINT_SCHEMA_VERSION = 3
private const val CHECKPOINT_MAGIC = 0x48534350
private const val MAX_ACTIVE_EPISODES = 5
private const val MAX_DECODED_CHECKPOINT_BYTES = 16 * 1024
internal val CHECKPOINT_LATCHED_ALARM_TYPES = setOf(
    SafetyAlarmType.NEAR_ELECTRIC,
    SafetyAlarmType.HEIGHT_LIMIT,
    SafetyAlarmType.SENSOR_FAULT,
    SafetyAlarmType.INACTIVITY,
)
