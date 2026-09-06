package com.example.helmet.hardware.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class SafePulseSpec(
    val resourceId: String,
    val activeDurationMillis: Long,
) {
    init {
        require(resourceId.isNotBlank())
        require(activeDurationMillis in 1..60_000)
    }
}

fun interface LogicalLineDriver {
    suspend fun setActive(active: Boolean)
}

class SafePulseExecutor(
    private val driver: LogicalLineDriver,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun pulse(spec: SafePulseSpec) {
        driver.setActive(false)
        try {
            driver.setActive(true)
            wait(spec.activeDurationMillis)
        } finally {
            withContext(NonCancellable) { driver.setActive(false) }
        }
    }
}

data class PowerSequenceStep(
    val resourceId: String,
    val active: Boolean,
    val rollbackActive: Boolean,
    val settleMillis: Long = 0,
) {
    init {
        require(resourceId.isNotBlank())
        require(settleMillis in 0..60_000)
    }
}

fun interface PowerLineDriver {
    suspend fun set(resourceId: String, active: Boolean)
}

class PowerSequenceException(
    message: String,
    cause: Throwable,
    val rollbackFailures: List<String>,
) : IllegalStateException(message, cause)

class PowerSequenceExecutor(
    private val driver: PowerLineDriver,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun execute(steps: List<PowerSequenceStep>) {
        require(steps.isNotEmpty())
        require(steps.map(PowerSequenceStep::resourceId).distinct().size == steps.size) {
            "power sequence repeats a resource"
        }
        val applied = mutableListOf<PowerSequenceStep>()
        try {
            steps.forEach { step ->
                driver.set(step.resourceId, step.active)
                applied += step
                if (step.settleMillis > 0) wait(step.settleMillis)
            }
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<String>()
            withContext(NonCancellable) {
                applied.asReversed().forEach { step ->
                    runCatching { driver.set(step.resourceId, step.rollbackActive) }
                        .onFailure { rollbackFailures += step.resourceId }
                }
            }
            if (error is CancellationException) throw error
            throw PowerSequenceException("power sequence failed and was rolled back", error, rollbackFailures)
        }
    }
}

enum class ButtonSignalType {
    PRESSED,
    RELEASED,
    SHORT_PRESS,
    LONG_PRESS,
}

data class ButtonSignal(
    val type: ButtonSignalType,
    val monotonicMillis: Long,
    val heldDurationMillis: Long,
)

data class ButtonInterpreterSnapshot(
    val connected: Boolean = true,
    val stablePressed: Boolean = false,
    val candidatePressed: Boolean = false,
    val candidateSinceMillis: Long = 0,
    val pressedSinceMillis: Long? = null,
    val longPressEmitted: Boolean = false,
    val lastMonotonicMillis: Long = 0,
)

class PhysicalButtonInterpreter(
    private val debounceMillis: Long = 40,
    private val longPressMillis: Long = 2_000,
    snapshot: ButtonInterpreterSnapshot = ButtonInterpreterSnapshot(),
) {
    init {
        require(debounceMillis in 1..1_000)
        require(longPressMillis > debounceMillis)
        require(snapshot.lastMonotonicMillis >= 0)
    }

    private var state = snapshot

    fun snapshot(): ButtonInterpreterSnapshot = state

    fun onRawLevel(pressed: Boolean, monotonicMillis: Long): List<ButtonSignal> {
        requireMonotonic(monotonicMillis)
        if (!state.connected) return emptyList()
        val signals = advanceStableState(monotonicMillis).toMutableList()
        if (pressed != state.candidatePressed) {
            state = state.copy(
                candidatePressed = pressed,
                candidateSinceMillis = monotonicMillis,
                lastMonotonicMillis = monotonicMillis,
            )
        } else {
            state = state.copy(lastMonotonicMillis = monotonicMillis)
        }
        return signals + advanceStableState(monotonicMillis)
    }

    fun advanceTo(monotonicMillis: Long): List<ButtonSignal> {
        requireMonotonic(monotonicMillis)
        if (!state.connected) return emptyList()
        state = state.copy(lastMonotonicMillis = monotonicMillis)
        return advanceStableState(monotonicMillis)
    }

    fun disconnect(monotonicMillis: Long) {
        requireMonotonic(monotonicMillis)
        state = ButtonInterpreterSnapshot(
            connected = false,
            lastMonotonicMillis = monotonicMillis,
            candidateSinceMillis = monotonicMillis,
        )
    }

    fun reconnect(initialPressed: Boolean, monotonicMillis: Long) {
        requireMonotonic(monotonicMillis)
        state = ButtonInterpreterSnapshot(
            connected = true,
            stablePressed = initialPressed,
            candidatePressed = initialPressed,
            candidateSinceMillis = monotonicMillis,
            pressedSinceMillis = if (initialPressed) monotonicMillis else null,
            lastMonotonicMillis = monotonicMillis,
        )
    }

    private fun advanceStableState(monotonicMillis: Long): List<ButtonSignal> {
        val signals = mutableListOf<ButtonSignal>()
        if (state.candidatePressed != state.stablePressed &&
            monotonicMillis - state.candidateSinceMillis >= debounceMillis
        ) {
            val transitionAt = state.candidateSinceMillis + debounceMillis
            if (state.candidatePressed) {
                state = state.copy(
                    stablePressed = true,
                    pressedSinceMillis = transitionAt,
                    longPressEmitted = false,
                )
                signals += ButtonSignal(ButtonSignalType.PRESSED, transitionAt, 0)
            } else {
                val pressedSince = state.pressedSinceMillis
                val duration = pressedSince?.let { (transitionAt - it).coerceAtLeast(0) } ?: 0
                state = state.copy(
                    stablePressed = false,
                    pressedSinceMillis = null,
                )
                signals += ButtonSignal(ButtonSignalType.RELEASED, transitionAt, duration)
                if (!state.longPressEmitted && pressedSince != null) {
                    signals += ButtonSignal(ButtonSignalType.SHORT_PRESS, transitionAt, duration)
                }
                state = state.copy(longPressEmitted = false)
            }
        }

        val pressedSince = state.pressedSinceMillis
        if (state.stablePressed && pressedSince != null && !state.longPressEmitted &&
            monotonicMillis - pressedSince >= longPressMillis
        ) {
            val emittedAt = pressedSince + longPressMillis
            state = state.copy(longPressEmitted = true)
            signals += ButtonSignal(ButtonSignalType.LONG_PRESS, emittedAt, longPressMillis)
        }
        return signals
    }

    private fun requireMonotonic(monotonicMillis: Long) {
        require(monotonicMillis >= state.lastMonotonicMillis) {
            "button event time moved backwards"
        }
    }
}

enum class ButtonBusinessAction {
    SOS,
    PHOTO,
    RECORD,
    CALL,
    INTERCOM,
}

data class PhysicalButtonMapping(
    val resourceId: String,
    val action: ButtonBusinessAction,
)

object PhysicalButtonMappingValidator {
    fun validate(mappings: List<PhysicalButtonMapping>): List<String> = buildList {
        mappings.groupBy(PhysicalButtonMapping::resourceId).filterValues { it.size > 1 }.keys.forEach {
            add("physical resource is mapped more than once: $it")
        }
        mappings.groupBy(PhysicalButtonMapping::action).filterValues { it.size > 1 }.keys.forEach {
            add("business action is mapped more than once: $it")
        }
        mappings.filterNot { mapping ->
            H618BoardProfile.profile.resources.any { it.id == mapping.resourceId && it.module == "LED_BUTTON" }
        }.forEach { add("resource is not a mapped H618 button GPIO: ${it.resourceId}") }
    }
}

enum class LocalLedState {
    OFF,
    RUNNING,
    NETWORK_OFFLINE,
    ACTIVITY,
    HARDWARE_FAULT,
    ALARM,
}

data class LocalLedInputs(
    val serviceRunning: Boolean,
    val networkValidated: Boolean,
    val recording: Boolean,
    val callActive: Boolean,
    val hardwareFault: Boolean,
    val alarmActive: Boolean,
)

data class LocalLedSnapshot(
    val desiredState: LocalLedState = LocalLedState.OFF,
)

class LocalLedStateMachine(snapshot: LocalLedSnapshot = LocalLedSnapshot()) {
    private var state = snapshot

    fun update(inputs: LocalLedInputs): LocalLedState {
        val desired = when {
            inputs.alarmActive -> LocalLedState.ALARM
            inputs.hardwareFault -> LocalLedState.HARDWARE_FAULT
            inputs.recording || inputs.callActive -> LocalLedState.ACTIVITY
            inputs.serviceRunning && !inputs.networkValidated -> LocalLedState.NETWORK_OFFLINE
            inputs.serviceRunning -> LocalLedState.RUNNING
            else -> LocalLedState.OFF
        }
        state = LocalLedSnapshot(desired)
        return desired
    }

    fun snapshot(): LocalLedSnapshot = state
}

enum class AxisSource {
    X,
    Y,
    Z,
}

data class AxisTransform(
    val source: AxisSource,
    val inverted: Boolean = false,
)

data class Mma8452AxisMapping(
    val x: AxisTransform,
    val y: AxisTransform,
    val z: AxisTransform,
) {
    init {
        require(setOf(x.source, y.source, z.source).size == 3) {
            "MMA8452 axis mapping must use each source axis exactly once"
        }
    }
}

data class Mma8452Sample(
    val xMilliG: Int,
    val yMilliG: Int,
    val zMilliG: Int,
    val monotonicMillis: Long,
)

class Mma8452StandardSampleAdapter(
    private val mapping: Mma8452AxisMapping,
) {
    fun fromMetresPerSecondSquared(
        x: Float,
        y: Float,
        z: Float,
        monotonicMillis: Long,
    ): Mma8452Sample {
        require(monotonicMillis >= 0)
        require(x.isFinite() && y.isFinite() && z.isFinite())
        val source = mapOf(AxisSource.X to x, AxisSource.Y to y, AxisSource.Z to z)
        fun convert(transform: AxisTransform): Int {
            val sign = if (transform.inverted) -1 else 1
            return (source.getValue(transform.source) / STANDARD_GRAVITY * 1_000f).roundToInt() * sign
        }
        return Mma8452Sample(
            xMilliG = convert(mapping.x),
            yMilliG = convert(mapping.y),
            zMilliG = convert(mapping.z),
            monotonicMillis = monotonicMillis,
        )
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665f
    }
}

enum class Mma8452Health {
    AVAILABLE,
    WAITING_EXTERNAL,
    STALE,
    DRIVER_MISSING,
    IDENTITY_MISMATCH,
    IO_FAILURE,
    DISCONNECTED,
}

class Mma8452HealthTracker(
    private val staleAfterMillis: Long = 30_000,
    private val expectedWhoAmI: Int? = null,
) {
    init {
        require(staleAfterMillis > 0)
        require(expectedWhoAmI == null || expectedWhoAmI in 0..0xFF)
    }

    private var state = Mma8452Health.WAITING_EXTERNAL
    private var lastSampleMillis: Long? = null

    fun driverMissing() {
        state = Mma8452Health.DRIVER_MISSING
        lastSampleMillis = null
    }

    fun connected(whoAmI: Int?) {
        state = when {
            expectedWhoAmI != null && whoAmI != expectedWhoAmI -> Mma8452Health.IDENTITY_MISMATCH
            else -> Mma8452Health.WAITING_EXTERNAL
        }
        lastSampleMillis = null
    }

    fun sample(monotonicMillis: Long) {
        require(monotonicMillis >= 0)
        lastSampleMillis?.let { require(monotonicMillis >= it) }
        lastSampleMillis = monotonicMillis
        state = Mma8452Health.AVAILABLE
    }

    fun ioFailure() {
        state = Mma8452Health.IO_FAILURE
    }

    fun disconnected() {
        state = Mma8452Health.DISCONNECTED
        lastSampleMillis = null
    }

    fun health(nowMonotonicMillis: Long): Mma8452Health {
        require(nowMonotonicMillis >= 0)
        val sampleTime = lastSampleMillis
        return if (state == Mma8452Health.AVAILABLE && sampleTime != null &&
            nowMonotonicMillis - sampleTime > staleAfterMillis
        ) {
            Mma8452Health.STALE
        } else {
            state
        }
    }
}
