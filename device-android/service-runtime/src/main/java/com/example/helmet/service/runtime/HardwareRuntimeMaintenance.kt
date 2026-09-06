package com.example.helmet.service.runtime

import com.example.helmet.hardware.api.HardwareGateway
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun nextHardwareMaintenanceGeneration(current: Long): Long {
    check(current in 1 until Long.MAX_VALUE) { "hardware gateway generation exhausted" }
    return current + 1
}

/**
 * Process-local lifecycle gate for the production hardware gateway.
 *
 * The debug-only board self-test uses this gate to pause UART ownership without stopping the
 * foreground service. No Android component exposes these operations outside the application.
 */
object HardwareRuntimeMaintenance {
    class Registration internal constructor(
        internal val generation: Long,
        internal val gateway: HardwareGateway,
    )

    class PauseLease internal constructor(
        internal val generation: Long,
    )

    class PauseOutcome internal constructor(
        val lease: PauseLease,
        val failure: Throwable?,
    )

    private val registrationLock = Any()
    private val lifecycleMutex = Mutex()

    @Volatile
    private var registration: Registration? = null

    private var nextGeneration = 1L
    private var pausedGeneration: Long? = null

    fun register(candidate: HardwareGateway): Registration = synchronized(registrationLock) {
        registration?.let { current ->
            check(current.gateway === candidate) {
                "a different hardware gateway is already registered"
            }
            return@synchronized current
        }
        val generation = nextGeneration
        nextGeneration = nextHardwareMaintenanceGeneration(generation)
        Registration(generation, candidate).also { created ->
            registration = created
            pausedGeneration = null
        }
    }

    suspend fun startRegistered(candidate: Registration): Boolean = lifecycleMutex.withLock {
        val shouldStart = synchronized(registrationLock) {
            registration === candidate && pausedGeneration != candidate.generation
        }
        if (!shouldStart) return@withLock false
        candidate.gateway.start()
        true
    }

    suspend fun pause(): PauseOutcome = lifecycleMutex.withLock {
        val current = synchronized(registrationLock) {
            checkNotNull(registration) { "hardware gateway is not registered" }
        }
        val lease = PauseLease(current.generation)
        val alreadyPaused = synchronized(registrationLock) {
            pausedGeneration == current.generation
        }
        if (!alreadyPaused) {
            try {
                current.gateway.stop()
            } catch (stopFailure: Throwable) {
                synchronized(registrationLock) {
                    check(registration === current) { "hardware gateway changed while pausing" }
                    pausedGeneration = current.generation
                }
                try {
                    current.gateway.start()
                    synchronized(registrationLock) {
                        if (registration === current && pausedGeneration == current.generation) {
                            pausedGeneration = null
                        }
                    }
                } catch (restartFailure: Throwable) {
                    stopFailure.addSuppressed(restartFailure)
                }
                return@withLock PauseOutcome(lease, stopFailure)
            }
            synchronized(registrationLock) {
                check(registration === current) { "hardware gateway changed while pausing" }
                pausedGeneration = current.generation
            }
        }
        PauseOutcome(lease, failure = null)
    }

    suspend fun resume(lease: PauseLease): Boolean = lifecycleMutex.withLock {
        resumeLocked(lease.generation)
    }

    suspend fun resumeCurrent(): Boolean = lifecycleMutex.withLock {
        val generation = synchronized(registrationLock) {
            registration?.generation
        } ?: return@withLock false
        resumeLocked(generation)
    }

    private suspend fun resumeLocked(generation: Long): Boolean {
        val current = synchronized(registrationLock) {
            registration?.takeIf { it.generation == generation }
        } ?: return false
        val alreadyResumed = synchronized(registrationLock) {
            when (pausedGeneration) {
                null -> true
                generation -> false
                else -> return false
            }
        }
        if (alreadyResumed) return true
        current.gateway.start()
        synchronized(registrationLock) {
            check(registration === current) { "hardware gateway changed while resuming" }
            check(pausedGeneration == generation) {
                "hardware maintenance lease changed while resuming"
            }
            pausedGeneration = null
        }
        return true
    }

    suspend fun unregisterAndStop(candidate: Registration): Boolean = lifecycleMutex.withLock {
        val registered = synchronized(registrationLock) {
            registration.takeIf { it === candidate }?.also {
                registration = null
                if (pausedGeneration == it.generation) pausedGeneration = null
            }
        } ?: return@withLock false
        registered.gateway.stop()
        true
    }
}
