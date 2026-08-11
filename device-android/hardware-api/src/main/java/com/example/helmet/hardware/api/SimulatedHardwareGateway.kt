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
) : HardwareGateway {
    private val mutableStatus = MutableStateFlow(HardwareStatus())
    private val mutableEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 32)
    private var heartbeatJob: Job? = null
    private var nextSampleReference = (monotonicClock() and 0xFFFF_FFFFL).coerceAtLeast(1L)

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: SharedFlow<HardwareEvent> = mutableEvents

    override suspend fun start() {
        if (heartbeatJob != null) return
        mutableStatus.value = HardwareStatus(
            connected = true,
            simulated = true,
            lastHeartbeatMillis = monotonicClock(),
            linkState = "CONNECTED",
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

    suspend fun inject(input: SimulatedInput) {
        val now = monotonicClock() and 0xFFFF_FFFFL
        val alarmType = when (input) {
            SimulatedInput.FALL -> "FALL"
            SimulatedInput.NEAR_ELECTRIC -> "NEAR_ELECTRIC"
            SimulatedInput.HEIGHT_LIMIT -> "HEIGHT_LIMIT"
            else -> null
        }
        if (alarmType != null) {
            val reference = nextSampleReference++
            val validFlags = when (input) {
                SimulatedInput.FALL -> 0x0001
                SimulatedInput.NEAR_ELECTRIC -> 0x0002
                SimulatedInput.HEIGHT_LIMIT -> 0x0004
                else -> 0
            }
            mutableEvents.emit(
                HardwareEvent.SensorSample(
                    monotonicMillis = now,
                    sampleReference = reference,
                    validFlags = validFlags,
                    accelerationXMilliG = 2_400.takeIf { input == SimulatedInput.FALL },
                    accelerationYMilliG = 0.takeIf { input == SimulatedInput.FALL },
                    accelerationZMilliG = 0.takeIf { input == SimulatedInput.FALL },
                    gyroXMilliDegreesPerSecond = 0.takeIf { input == SimulatedInput.FALL },
                    gyroYMilliDegreesPerSecond = 0.takeIf { input == SimulatedInput.FALL },
                    gyroZMilliDegreesPerSecond = 0.takeIf { input == SimulatedInput.FALL },
                    electricFieldMilliVolts = 920.takeIf { input == SimulatedInput.NEAR_ELECTRIC },
                    pressurePascals = 101_325L.takeIf { input == SimulatedInput.HEIGHT_LIMIT },
                    temperatureCentiCelsius = null,
                    altitudeMillimetres = 2_200.takeIf { input == SimulatedInput.HEIGHT_LIMIT },
                    simulated = true,
                ),
            )
            mutableEvents.emit(
                HardwareEvent.Alarm(
                    monotonicMillis = now,
                    alarmType = alarmType,
                    severity = if (input == SimulatedInput.NEAR_ELECTRIC) "CRITICAL" else "HIGH",
                    simulated = true,
                    active = true,
                    alarmId = reference,
                    configVersion = 1,
                    sampleReference = reference,
                    localActions = 0,
                    sensorFaults = 0,
                    origin = HardwareAlarmOrigin.SIMULATOR,
                ),
            )
            return
        }
        mutableEvents.emit(HardwareEvent.Key(now, input))
    }
}
