package com.example.helmet.hardware.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.core.protocol.HslStreamDecoder
import com.example.helmet.hardware.api.IHelmetHardwareCallback
import com.example.helmet.hardware.api.IHelmetHardwareService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HelmetHardwareService : Service() {
    private val serialPort = NativeSerialPort()
    private val decoder = HslStreamDecoder()
    private val callbacks = RemoteCallbackList<IHelmetHardwareCallback>()
    private val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "helmet-uart-reader")
    }
    private val generation = AtomicInteger()
    private val receiveBytes = AtomicLong()
    private val transmitBytes = AtomicLong()
    private val transmitFrames = AtomicLong()
    private val receiveFrames = AtomicLong()
    private val lock = Any()

    @Volatile
    private var fileDescriptor = CLOSED_FILE_DESCRIPTOR
    @Volatile
    private var currentPath = ""
    @Volatile
    private var currentBaudRate = 0
    @Volatile
    private var lastError = ""

    private val binder = object : IHelmetHardwareService.Stub() {
        override fun openPort(devicePath: String, baudRate: Int) {
            validateOpenArguments(devicePath, baudRate)
            synchronized(lock) {
                closeLocked(notify = false)
                decoder.reset()
                val descriptor = serialPort.open(devicePath, baudRate)
                fileDescriptor = descriptor
                currentPath = devicePath
                currentBaudRate = baudRate
                lastError = ""
                val readerGeneration = generation.incrementAndGet()
                readerExecutor.execute { readLoop(descriptor, readerGeneration) }
            }
            notifyLinkState(HslLinkState.CONNECTED, "port_open")
        }

        override fun closePort() {
            synchronized(lock) { closeLocked(notify = true) }
        }

        override fun sendFrame(type: Int, flags: Int, sequence: Int, payload: ByteArray) {
            val frame = HslFrame(
                flags = flags,
                type = type,
                sequence = sequence,
                payload = payload.copyOf(),
            )
            val bytes = HslFrameCodec.encode(frame)
            val descriptor = fileDescriptor
            check(descriptor != CLOSED_FILE_DESCRIPTOR) { "serial port is closed" }
            val written = serialPort.write(descriptor, bytes)
            check(written == bytes.size) { "short serial write: $written/${bytes.size}" }
            transmitBytes.addAndGet(written.toLong())
            transmitFrames.incrementAndGet()
        }

        override fun getDiagnostics(): Bundle = diagnostics()

        override fun registerCallback(callback: IHelmetHardwareCallback) {
            callbacks.register(callback)
        }

        override fun unregisterCallback(callback: IHelmetHardwareCallback) {
            callbacks.unregister(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) { closeLocked(notify = false) }
        callbacks.kill()
        readerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun readLoop(descriptor: Int, readerGeneration: Int) {
        val chunk = ByteArray(512)
        try {
            while (readerGeneration == generation.get() && descriptor == fileDescriptor) {
                val count = serialPort.read(descriptor, chunk, READ_TIMEOUT_MILLIS)
                if (count <= 0) continue
                receiveBytes.addAndGet(count.toLong())
                decoder.feed(chunk.copyOf(count)).forEach { frame ->
                    receiveFrames.incrementAndGet()
                    notifyFrame(frame)
                }
            }
        } catch (error: Throwable) {
            if (readerGeneration == generation.get()) {
                lastError = error.toString()
                notifyLinkState(HslLinkState.FAULT, lastError)
            }
        }
    }

    private fun notifyFrame(frame: HslFrame) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching {
                    callbacks.getBroadcastItem(index).onFrame(
                        frame.version,
                        frame.flags,
                        frame.type,
                        frame.sequence,
                        frame.payload,
                    )
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun notifyLinkState(state: HslLinkState, detail: String) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching {
                    callbacks.getBroadcastItem(index).onLinkStateChanged(state.ordinal, detail)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun diagnostics(): Bundle {
        val stats = decoder.stats
        return Bundle().apply {
            putBoolean("isOpen", fileDescriptor != CLOSED_FILE_DESCRIPTOR)
            putString("devicePath", currentPath)
            putInt("baudRate", currentBaudRate)
            putLong("receiveBytes", receiveBytes.get())
            putLong("transmitBytes", transmitBytes.get())
            putLong("receiveFrames", receiveFrames.get())
            putLong("transmitFrames", transmitFrames.get())
            putLong("crcErrors", stats.crcErrors)
            putLong("lengthErrors", stats.lengthErrors)
            putLong("versionErrors", stats.versionErrors)
            putLong("discardedBytes", stats.discardedBytes)
            putString("lastError", lastError)
        }
    }

    private fun closeLocked(notify: Boolean) {
        generation.incrementAndGet()
        val descriptor = fileDescriptor
        fileDescriptor = CLOSED_FILE_DESCRIPTOR
        currentPath = ""
        currentBaudRate = 0
        if (descriptor != CLOSED_FILE_DESCRIPTOR) {
            runCatching { serialPort.close(descriptor) }
        }
        if (notify) notifyLinkState(HslLinkState.DISCONNECTED, "port_closed")
    }

    private fun validateOpenArguments(devicePath: String, baudRate: Int) {
        require(DEVICE_PATH_PATTERN.matches(devicePath)) { "device path is not an allowed serial node" }
        require(baudRate in SUPPORTED_BAUD_RATES) { "unsupported baud rate" }
    }

    companion object {
        private const val CLOSED_FILE_DESCRIPTOR = -1
        private const val READ_TIMEOUT_MILLIS = 250
        private val DEVICE_PATH_PATTERN = Regex("^/dev/tty(?:AS|S|USB|ACM)[0-9]{1,3}$")
        private val SUPPORTED_BAUD_RATES = setOf(9_600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600)
    }
}
