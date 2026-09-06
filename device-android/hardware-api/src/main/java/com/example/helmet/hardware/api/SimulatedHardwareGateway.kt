package com.example.helmet.hardware.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SimulatedHardwareGateway(
    private val scope: CoroutineScope,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val durableSampleReferenceHighWater: suspend () -> Long? = { null },
) : HardwareGateway {
    private val mutableStatus = MutableStateFlow(
        HardwareStatus(compatibility = HardwareCompatibility.NOT_APPLICABLE),
    )
    private val mutableEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 32)
    private var heartbeatJob: Job? = null
    private var nextSampleReference = (monotonicClock() and 0xFFFF_FFFFL).coerceAtLeast(1L)

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: SharedFlow<HardwareEvent> = mutableEvents

    override suspend fun start() {
        if (heartbeatJob != null) return
        durableSampleReferenceHighWater()?.let { highWater ->
            require(highWater in 0 until MAX_SAMPLE_REFERENCE) {
                "durable sample reference space is exhausted"
            }
            nextSampleReference = maxOf(nextSampleReference, highWater + 1L)
        }
        mutableStatus.value = HardwareStatus(
            connected = true,
            simulated = true,
            lastHeartbeatMillis = monotonicClock(),
            linkState = "CONNECTED",
            compatibility = HardwareCompatibility.NOT_APPLICABLE,
        )
        heartbeatJob = scope.launch {
            while (isActive) {
                val now = monotonicClock()
                mutableStatus.value = mutableStatus.value.copy(lastHeartbeatMillis = now)
                mutableEvents.emit(HardwareEvent.Heartbeat(now))
                delay(1_000)
            }
        }
    }

    override suspend fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        mutableStatus.value = mutableStatus.value.copy(connected = false, linkState = "DISCONNECTED")
    }

    override suspend fun send(command: HardwareCommand) {
        require(command.payload.size <= 1024)
    }

    override suspend fun sendForResult(command: HardwareCommand): HardwareCommandResult {
        send(command)
        return HardwareCommandResult(
            outcome = if (command.flags and com.example.helmet.core.protocol.HslFlags.ACK_REQUIRED != 0) {
                HardwareCommandOutcome.ACKNOWLEDGED
            } else {
                HardwareCommandOutcome.SENT
            },
            type = command.type,
            businessSequence = command.sequence,
            wireSequence = command.sequence,
            resultCode = 0,
        )
    }

    suspend fun inject(input: SimulatedInput) {
        val now = monotonicClock() and 0xFFFF_FFFFL
        if (input == SimulatedInput.FALL) {
            check(nextSampleReference <= MAX_SAMPLE_REFERENCE - 3L) {
                "simulated safety sample reference space is exhausted"
            }
            val base = now.takeIf { it in 1..0xFFFF_FE00L } ?: 1L
            listOf(
                SimulatedImuSample(0, 0, 0, 1_000),
                SimulatedImuSample(100, 0, 0, 100),
                SimulatedImuSample(240, 0, 0, 100),
                SimulatedImuSample(300, 2_400, 0, 0),
            ).forEach { sample ->
                mutableEvents.emit(
                    HardwareEvent.SensorSample(
                        monotonicMillis = base + sample.offsetMillis,
                        sampleReference = allocateSampleReference(),
                        validFlags = 0x0001,
                        accelerationXMilliG = sample.xMilliG,
                        accelerationYMilliG = sample.yMilliG,
                        accelerationZMilliG = sample.zMilliG,
                        gyroXMilliDegreesPerSecond = 0,
                        gyroYMilliDegreesPerSecond = 0,
                        gyroZMilliDegreesPerSecond = 0,
                        electricFieldMilliVolts = null,
                        pressurePascals = null,
                        temperatureCentiCelsius = null,
                        altitudeMillimetres = null,
                        simulated = true,
                    ),
                )
            }
            return
        }
        if (input == SimulatedInput.NEAR_ELECTRIC) {
            val values = List(10) { 100 } + List(3) { 920 }
            check(nextSampleReference <= MAX_SAMPLE_REFERENCE - (values.size - 1L)) {
                "simulated safety sample reference space is exhausted"
            }
            val lastOffsetMillis = (values.size - 1L) * 100L
            val base = now.takeIf { it in 1..MAX_SAMPLE_REFERENCE - lastOffsetMillis } ?: 1L
            values.forEachIndexed { index, electricFieldMilliVolts ->
                mutableEvents.emit(
                    HardwareEvent.SensorSample(
                        monotonicMillis = base + index * 100L,
                        sampleReference = allocateSampleReference(),
                        validFlags = 0x0002,
                        accelerationXMilliG = null,
                        accelerationYMilliG = null,
                        accelerationZMilliG = null,
                        gyroXMilliDegreesPerSecond = null,
                        gyroYMilliDegreesPerSecond = null,
                        gyroZMilliDegreesPerSecond = null,
                        electricFieldMilliVolts = electricFieldMilliVolts,
                        pressurePascals = null,
                        temperatureCentiCelsius = null,
                        altitudeMillimetres = null,
                        simulated = true,
                    ),
                )
            }
            return
        }
        if (input == SimulatedInput.HEIGHT_LIMIT) {
            val pressures = List(10) { 101_325L } + List(3) { 101_285L }
            check(nextSampleReference <= MAX_SAMPLE_REFERENCE - (pressures.size - 1L)) {
                "simulated safety sample reference space is exhausted"
            }
            val lastOffsetMillis = (pressures.size - 1L) * 100L
            val base = now.takeIf { it in 1..MAX_SAMPLE_REFERENCE - lastOffsetMillis } ?: 1L
            pressures.forEachIndexed { index, pressurePascals ->
                mutableEvents.emit(
                    HardwareEvent.SensorSample(
                        monotonicMillis = base + index * 100L,
                        sampleReference = allocateSampleReference(),
                        validFlags = 0x0010,
                        accelerationXMilliG = null,
                        accelerationYMilliG = null,
                        accelerationZMilliG = null,
                        gyroXMilliDegreesPerSecond = null,
                        gyroYMilliDegreesPerSecond = null,
                        gyroZMilliDegreesPerSecond = null,
                        electricFieldMilliVolts = null,
                        pressurePascals = pressurePascals,
                        temperatureCentiCelsius = null,
                        altitudeMillimetres = null,
                        simulated = true,
                    ),
                )
            }
            return
        }
        mutableEvents.emit(HardwareEvent.Key(now, input))
    }

    private data class SimulatedImuSample(
        val offsetMillis: Long,
        val xMilliG: Int,
        val yMilliG: Int,
        val zMilliG: Int,
    )

    private fun allocateSampleReference(): Long {
        check(nextSampleReference in 1..MAX_SAMPLE_REFERENCE) {
            "simulated safety sample reference space is exhausted"
        }
        return nextSampleReference++
    }

    private companion object {
        const val MAX_SAMPLE_REFERENCE = 0xFFFF_FFFFL
    }
}
