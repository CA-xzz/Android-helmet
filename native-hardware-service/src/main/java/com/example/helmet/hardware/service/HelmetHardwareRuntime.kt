package com.example.helmet.hardware.service

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import com.example.helmet.core.protocol.HslDecoderStats
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.core.protocol.HslStreamDecoder
import com.example.helmet.hardware.api.IHelmetHardwareCallback
import com.example.helmet.hardware.api.IHelmetHardwareService
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Process-local UART runtime owned by the private hardware provider. */
internal class HelmetHardwareRuntime(
    private val applicationUid: Int,
) : Closeable {
    private val serialPort = NativeSerialPort()
    private val callbacks = object : RemoteCallbackList<IHelmetHardwareCallback>() {
        override fun onCallbackDied(callback: IHelmetHardwareCallback) {
            if (registeredCallbackCount == 0) closePortAfterCallbackDeath()
        }
    }
    private val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "helmet-uart-reader")
    }
    private val generation = AtomicInteger()
    private val receiveBytes = AtomicLong()
    private val transmitBytes = AtomicLong()
    private val transmitFrames = AtomicLong()
    private val receiveFrames = AtomicLong()
    private val portLifecycleLock = Any()
    private val lock = Any()
    private val readIoLock = Any()
    private val callbackLock = Any()

    @Volatile
    private var currentSession: PortSession? = null

    @Volatile
    private var lastError = ""

    @Volatile
    private var latestDecoderStats = HslDecoderStats()

    private var destroyed = false

    val binder: IBinder = object : IHelmetHardwareService.Stub() {
        override fun openPort(devicePath: String, baudRate: Int) {
            enforceInternalCaller()
            validateOpenArguments(devicePath, baudRate)
            synchronized(portLifecycleLock) {
                synchronized(lock) {
                    check(!destroyed) { "hardware runtime is destroyed" }
                }
                closeCurrentSession()
                openSession(devicePath, baudRate)
            }
        }

        override fun closePort() {
            enforceInternalCaller()
            // A terminal callback cannot be tied to a current session after invalidation. The
            // gateway that requested the close owns its local disconnected state.
            synchronized(portLifecycleLock) { closeCurrentSession() }
        }

        override fun sendFrame(type: Int, flags: Int, sequence: Int, payload: ByteArray) {
            enforceInternalCaller()
            val frame = HslFrame(
                flags = flags,
                type = type,
                sequence = sequence,
                payload = payload.copyOf(),
            )
            val bytes = HslFrameCodec.encode(frame)
            synchronized(lock) {
                check(!destroyed) { "hardware runtime is destroyed" }
                val session = checkNotNull(currentSession) { "serial port is closed" }
                val written = serialPort.write(session.descriptor, bytes)
                check(written == bytes.size) { "short serial write: $written/${bytes.size}" }
                transmitBytes.addAndGet(written.toLong())
                transmitFrames.incrementAndGet()
            }
        }

        override fun getDiagnostics(): Bundle {
            enforceInternalCaller()
            return diagnostics()
        }

        override fun registerCallback(callback: IHelmetHardwareCallback) {
            enforceInternalCaller()
            synchronized(lock) { check(!destroyed) { "hardware runtime is destroyed" } }
            check(callbacks.register(callback)) { "hardware callback registration rejected" }
        }

        override fun unregisterCallback(callback: IHelmetHardwareCallback) {
            enforceInternalCaller()
            callbacks.unregister(callback)
        }
    }

    override fun close() {
        synchronized(portLifecycleLock) {
            val shouldClose = synchronized(lock) {
                if (destroyed) {
                    false
                } else {
                    destroyed = true
                    true
                }
            }
            if (!shouldClose) return
            closeCurrentSession()
        }
        synchronized(callbackLock) { callbacks.kill() }
        readerExecutor.shutdownNow()
    }

    private fun enforceInternalCaller() {
        if (!isInternalHardwareCaller(Binder.getCallingUid(), applicationUid)) {
            throw SecurityException("hardware caller UID is not the application UID")
        }
    }

    private fun closePortAfterCallbackDeath() {
        synchronized(portLifecycleLock) {
            val shouldClose = synchronized(lock) {
                if (destroyed) {
                    false
                } else {
                    lastError = "CLIENT_CALLBACK_DIED"
                    true
                }
            }
            if (shouldClose) closeCurrentSession()
        }
    }

    private fun readLoop(session: PortSession) {
        val chunk = ByteArray(512)
        try {
            while (isCurrent(session)) {
                val count = synchronized(readIoLock) {
                    if (!isCurrent(session)) return
                    serialPort.read(session.descriptor, chunk, READ_TIMEOUT_MILLIS)
                }
                if (count <= 0) continue
                val frames = synchronized(lock) {
                    if (currentSession !== session) return@synchronized null
                    receiveBytes.addAndGet(count.toLong())
                    session.decoder.feed(chunk.copyOf(count)).also {
                        latestDecoderStats = session.decoder.stats
                    }
                } ?: return
                frames.forEach { frame ->
                    if (!notifyFrame(session, frame)) return
                }
            }
        } catch (error: Throwable) {
            val failureCode = serialReadFailureCode(error)
            val current = synchronized(lock) {
                if (isCurrent(session)) {
                    lastError = failureCode
                    true
                } else {
                    false
                }
            }
            if (current) notifyLinkState(session, HslLinkState.FAULT, failureCode)
        }
    }

    private fun notifyFrame(session: PortSession, frame: HslFrame): Boolean =
        session.callbackBarrier.dispatchIfCurrent(session, ::currentSessionSnapshot) {
            receiveFrames.incrementAndGet()
            callbackSnapshot().forEach { callback ->
                runCatching {
                    callback.onFrame(
                        frame.version,
                        frame.flags,
                        frame.type,
                        frame.sequence,
                        frame.payload,
                    )
                }
            }
        }

    private fun notifyLinkState(session: PortSession, state: HslLinkState, detail: String): Boolean =
        session.callbackBarrier.dispatchIfCurrent(session, ::currentSessionSnapshot) {
            callbackSnapshot().forEach { callback ->
                runCatching { callback.onLinkStateChanged(state.ordinal, detail) }
            }
        }

    private fun callbackSnapshot(): List<IHelmetHardwareCallback> = synchronized(callbackLock) {
        val snapshot = mutableListOf<IHelmetHardwareCallback>()
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) snapshot += callbacks.getBroadcastItem(index)
        } finally {
            callbacks.finishBroadcast()
        }
        snapshot
    }

    private fun currentSessionSnapshot(): PortSession? = currentSession

    private fun openSession(devicePath: String, baudRate: Int): PortSession {
        val descriptor = serialPort.open(devicePath, baudRate)
        val session = PortSession(
            descriptor = descriptor,
            generation = generation.incrementAndGet(),
            devicePath = devicePath,
            baudRate = baudRate,
            decoder = HslStreamDecoder(),
        )
        try {
            synchronized(lock) {
                check(!destroyed) { "hardware runtime is destroyed" }
                check(currentSession == null) { "serial port is already open" }
                lastError = ""
                latestDecoderStats = HslDecoderStats()
                currentSession = session
            }
            readerExecutor.execute {
                if (notifyLinkState(session, HslLinkState.CONNECTED, "port_open")) {
                    readLoop(session)
                }
            }
            return session
        } catch (error: Throwable) {
            val detached = synchronized(lock) {
                if (currentSession === session) {
                    currentSession = null
                    generation.incrementAndGet()
                    session.callbackBarrier.invalidate()
                    true
                } else {
                    false
                }
            }
            if (detached) session.callbackBarrier.awaitDrained()
            runCatching { serialPort.close(descriptor) }
            throw error
        }
    }

    private fun closeCurrentSession() {
        val session = synchronized(lock) {
            generation.incrementAndGet()
            currentSession.also { current ->
                currentSession = null
                // Admission is disabled while the session identity change is still under lock.
                // Waiting happens below, without holding the runtime state lock.
                current?.callbackBarrier?.invalidate()
            }
        } ?: return

        synchronized(readIoLock) {
            runCatching { serialPort.close(session.descriptor) }
        }
        session.callbackBarrier.awaitDrained()
        latestDecoderStats = session.decoder.stats
    }

    private fun diagnostics(): Bundle {
        val snapshot = synchronized(lock) {
            val session = currentSession
            DiagnosticsSnapshot(
                isOpen = session != null,
                devicePath = session?.devicePath.orEmpty(),
                baudRate = session?.baudRate ?: 0,
                decoderStats = session?.decoder?.stats ?: latestDecoderStats,
            )
        }
        val stats = snapshot.decoderStats
        return Bundle().apply {
            putBoolean("isOpen", snapshot.isOpen)
            putString("devicePath", snapshot.devicePath)
            putInt("baudRate", snapshot.baudRate)
            putLong("receiveBytes", receiveBytes.get())
            putLong("transmitBytes", transmitBytes.get())
            putLong("receiveFrames", receiveFrames.get())
            putLong("transmitFrames", transmitFrames.get())
            putLong("crcErrors", stats.crcErrors)
            putLong("lengthErrors", stats.lengthErrors)
            putLong("versionErrors", stats.versionErrors)
            putLong("discardedBytes", stats.discardedBytes)
            putInt("registeredCallbacks", callbacks.registeredCallbackCount)
            putString("lastError", lastError)
        }
    }

    private fun isCurrent(session: PortSession): Boolean =
        currentSession === session && generation.get() == session.generation

    private fun validateOpenArguments(devicePath: String, baudRate: Int) {
        require(SerialDevicePathPolicy.isAllowed(devicePath, BuildConfig.ALLOW_TEST_PTY)) {
            "device path is not an allowed serial node"
        }
        require(baudRate in SUPPORTED_BAUD_RATES) { "unsupported baud rate" }
    }

    private data class PortSession(
        val descriptor: Int,
        val generation: Int,
        val devicePath: String,
        val baudRate: Int,
        val decoder: HslStreamDecoder,
        val callbackBarrier: SessionCallbackBarrier = SessionCallbackBarrier(),
    )

    private data class DiagnosticsSnapshot(
        val isOpen: Boolean,
        val devicePath: String,
        val baudRate: Int,
        val decoderStats: HslDecoderStats,
    )

    companion object {
        private const val READ_TIMEOUT_MILLIS = 250
        private val SUPPORTED_BAUD_RATES = setOf(
            9_600,
            19_200,
            38_400,
            57_600,
            115_200,
            230_400,
            460_800,
            921_600,
        )
    }
}

/**
 * A session callback either finishes before invalidation drains or is rejected afterwards.
 * The action runs without the barrier monitor, so Binder callback delivery cannot block invalidation
 * while holding the monitor that its completion needs.
 */
internal class SessionCallbackBarrier {
    private val monitor = Object()
    private var valid = true
    private var activeDispatches = 0

    fun dispatch(action: () -> Unit): Boolean {
        synchronized(monitor) {
            if (!valid) return false
            activeDispatches += 1
        }
        try {
            action()
        } finally {
            synchronized(monitor) {
                check(activeDispatches > 0)
                activeDispatches -= 1
                if (activeDispatches == 0) monitor.notifyAll()
            }
        }
        return true
    }

    fun invalidate() {
        synchronized(monitor) { valid = false }
    }

    fun awaitDrained() {
        var interrupted = false
        synchronized(monitor) {
            while (activeDispatches != 0) {
                try {
                    monitor.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal fun <T : Any> SessionCallbackBarrier.dispatchIfCurrent(
    session: T,
    currentSession: () -> T?,
    action: () -> Unit,
): Boolean {
    var delivered = false
    dispatch {
        if (currentSession() === session) {
            delivered = true
            action()
        }
    }
    return delivered
}

internal fun isInternalHardwareCaller(callingUid: Int, applicationUid: Int): Boolean =
    callingUid == applicationUid

internal fun serialReadFailureCode(error: Throwable): String {
    val errorType = error.javaClass.name.takeIf(SAFE_ERROR_TYPE::matches)
        ?: Throwable::class.java.name
    return "SERIAL_READ_FAILED:$errorType"
}

private val SAFE_ERROR_TYPE = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,255}")
