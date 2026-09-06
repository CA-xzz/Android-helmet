package com.example.helmet.hardware.service

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.hardware.api.IRtkSerialCallback
import com.example.helmet.hardware.api.IRtkSerialService
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Raw UART4 runtime. It is isolated from the framed HSL UART2 runtime. */
internal class RtkSerialRuntime(
    private val applicationUid: Int,
) : Closeable {
    private val serialPort = NativeSerialPort()
    private val callbacks = object : RemoteCallbackList<IRtkSerialCallback>() {
        override fun onCallbackDied(callback: IRtkSerialCallback) {
            if (registeredCallbackCount == 0) closePortAfterCallbackDeath()
        }
    }
    private val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "helmet-rtk-uart-reader")
    }
    private val generation = AtomicInteger()
    private val receiveBytes = AtomicLong()
    private val transmitBytes = AtomicLong()
    private val lifecycleLock = Any()
    private val stateLock = Any()
    private val readIoLock = Any()
    private val callbackLock = Any()

    @Volatile
    private var currentSession: Session? = null

    @Volatile
    private var lastError = ""

    private var destroyed = false

    val binder: IBinder = object : IRtkSerialService.Stub() {
        override fun openPort(devicePath: String, baudRate: Int) {
            enforceInternalCaller()
            validateOpenArguments(devicePath, baudRate)
            synchronized(lifecycleLock) {
                synchronized(stateLock) { check(!destroyed) { "RTK runtime is destroyed" } }
                closeCurrentSession()
                openSession(devicePath, baudRate)
            }
        }

        override fun closePort() {
            enforceInternalCaller()
            synchronized(lifecycleLock) { closeCurrentSession() }
        }

        override fun write(bytes: ByteArray) {
            enforceInternalCaller()
            require(bytes.isNotEmpty() && bytes.size <= MAX_WRITE_SIZE) { "invalid RTK write size" }
            synchronized(stateLock) {
                check(!destroyed) { "RTK runtime is destroyed" }
                val session = checkNotNull(currentSession) { "RTK serial port is closed" }
                val written = serialPort.write(session.descriptor, bytes)
                check(written == bytes.size) { "short RTK serial write: $written/${bytes.size}" }
                transmitBytes.addAndGet(written.toLong())
            }
        }

        override fun getDiagnostics(): Bundle {
            enforceInternalCaller()
            return diagnostics()
        }

        override fun registerCallback(callback: IRtkSerialCallback) {
            enforceInternalCaller()
            synchronized(stateLock) { check(!destroyed) { "RTK runtime is destroyed" } }
            check(callbacks.register(callback)) { "RTK callback registration rejected" }
        }

        override fun unregisterCallback(callback: IRtkSerialCallback) {
            enforceInternalCaller()
            callbacks.unregister(callback)
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            val shouldClose = synchronized(stateLock) {
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

    private fun openSession(devicePath: String, baudRate: Int) {
        val descriptor = serialPort.open(devicePath, baudRate)
        val session = Session(descriptor, generation.incrementAndGet(), devicePath, baudRate)
        try {
            synchronized(stateLock) {
                check(!destroyed) { "RTK runtime is destroyed" }
                check(currentSession == null) { "RTK serial port is already open" }
                currentSession = session
                lastError = ""
            }
            readerExecutor.execute {
                if (notifyLinkState(session, HslLinkState.CONNECTED, "rtk_port_open")) readLoop(session)
            }
        } catch (error: Throwable) {
            val detached = synchronized(stateLock) {
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

    private fun readLoop(session: Session) {
        val buffer = ByteArray(READ_SIZE)
        try {
            while (isCurrent(session)) {
                val count = synchronized(readIoLock) {
                    if (!isCurrent(session)) return
                    serialPort.read(session.descriptor, buffer, READ_TIMEOUT_MILLIS)
                }
                if (count <= 0) continue
                receiveBytes.addAndGet(count.toLong())
                if (!notifyBytes(session, buffer.copyOf(count))) return
            }
        } catch (error: Throwable) {
            val failure = serialReadFailureCode(error)
            val current = synchronized(stateLock) {
                if (isCurrent(session)) {
                    lastError = failure
                    true
                } else {
                    false
                }
            }
            if (current) notifyLinkState(session, HslLinkState.FAULT, failure)
        }
    }

    private fun closeCurrentSession() {
        val session = synchronized(stateLock) {
            generation.incrementAndGet()
            currentSession.also { current ->
                currentSession = null
                current?.callbackBarrier?.invalidate()
            }
        } ?: return
        synchronized(readIoLock) { runCatching { serialPort.close(session.descriptor) } }
        session.callbackBarrier.awaitDrained()
    }

    private fun closePortAfterCallbackDeath() {
        synchronized(lifecycleLock) {
            val shouldClose = synchronized(stateLock) {
                if (destroyed) {
                    false
                } else {
                    lastError = "RTK_CLIENT_CALLBACK_DIED"
                    true
                }
            }
            if (shouldClose) closeCurrentSession()
        }
    }

    private fun notifyBytes(session: Session, bytes: ByteArray): Boolean =
        session.callbackBarrier.dispatchIfCurrent(session, ::currentSessionSnapshot) {
            callbackSnapshot().forEach { callback -> runCatching { callback.onBytes(bytes) } }
        }

    private fun notifyLinkState(session: Session, state: HslLinkState, detail: String): Boolean =
        session.callbackBarrier.dispatchIfCurrent(session, ::currentSessionSnapshot) {
            callbackSnapshot().forEach { callback ->
                runCatching { callback.onLinkStateChanged(state.ordinal, detail) }
            }
        }

    private fun callbackSnapshot(): List<IRtkSerialCallback> = synchronized(callbackLock) {
        val snapshot = mutableListOf<IRtkSerialCallback>()
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) snapshot += callbacks.getBroadcastItem(index)
        } finally {
            callbacks.finishBroadcast()
        }
        snapshot
    }

    private fun diagnostics(): Bundle {
        val session = currentSessionSnapshot()
        return Bundle().apply {
            putBoolean("isOpen", session != null)
            putString("devicePath", session?.devicePath.orEmpty())
            putInt("baudRate", session?.baudRate ?: 0)
            putLong("receiveBytes", receiveBytes.get())
            putLong("transmitBytes", transmitBytes.get())
            putInt("registeredCallbacks", callbacks.registeredCallbackCount)
            putString("lastError", lastError)
        }
    }

    private fun validateOpenArguments(devicePath: String, baudRate: Int) {
        require(
            devicePath == PRODUCTION_RTK_DEVICE_PATH ||
                (BuildConfig.ALLOW_TEST_PTY && VariantSerialDevicePathPolicy.acceptsAdditionalPath(devicePath)),
        ) { "RTK device path is not allowed" }
        require(baudRate in SUPPORTED_BAUD_RATES) { "unsupported RTK baud rate" }
    }

    private fun enforceInternalCaller() {
        if (!isInternalHardwareCaller(Binder.getCallingUid(), applicationUid)) {
            throw SecurityException("RTK hardware caller UID is not the application UID")
        }
    }

    private fun currentSessionSnapshot(): Session? = currentSession

    private fun isCurrent(session: Session): Boolean =
        currentSession === session && generation.get() == session.generation

    private data class Session(
        val descriptor: Int,
        val generation: Int,
        val devicePath: String,
        val baudRate: Int,
        val callbackBarrier: SessionCallbackBarrier = SessionCallbackBarrier(),
    )

    companion object {
        private const val PRODUCTION_RTK_DEVICE_PATH = "/dev/ttyAS4"
        private const val READ_TIMEOUT_MILLIS = 250
        private const val READ_SIZE = 1_024
        private const val MAX_WRITE_SIZE = 4_096
        private val SUPPORTED_BAUD_RATES = setOf(
            9_600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600,
        )
    }
}
