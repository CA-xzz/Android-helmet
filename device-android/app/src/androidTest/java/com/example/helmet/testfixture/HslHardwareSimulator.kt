package com.example.helmet.testfixture

import com.example.helmet.core.protocol.HslAck
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslHeartbeat
import com.example.helmet.core.protocol.HslHelloCodec
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslModuleHello
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslSensorSample
import com.example.helmet.core.protocol.HslStreamDecoder
import com.example.helmet.hardware.api.HslModuleContract
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Debug-only external-module endpoint. Its output is never treated as real hardware evidence. */
class HslHardwareSimulator : Closeable {
    private val pty = AndroidTestPtyFixture()
    private val decoder = HslStreamDecoder()
    private val writes = Any()
    private val acknowledgementSequence = AtomicInteger(0x6000)
    private val outboundFrames = Channel<HslFrame>(Channel.UNLIMITED)
    private var responder: Job? = null

    val devicePath: String
        get() = pty.slavePath

    fun start(scope: CoroutineScope) {
        check(responder == null)
        responder = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = try {
                    pty.read(maximumBytes = 4_096, timeoutMillis = 100)
                } catch (_: IOException) {
                    if (!isActive) break
                    delay(25)
                    continue
                }
                decoder.feed(bytes).forEach { frame ->
                    outboundFrames.send(frame)
                    if (frame.type != HslMessageType.ACK && frame.flags and HslFlags.ACK_REQUIRED != 0) {
                        sendFrame(
                            type = HslMessageType.ACK,
                            flags = HslFlags.ACK,
                            sequence = acknowledgementSequence.getAndUpdate { (it + 1) and 0xFFFF },
                            payload = HslPayloadCodec.encodeAck(HslAck(frame.sequence, 0)),
                        )
                    }
                }
            }
        }
    }

    fun sendHello(sequence: Int = 1, capabilityMask: Long = HslCapability.KNOWN_MASK) {
        val contract = HslModuleContract.version
        sendFrame(
            type = HslMessageType.HELLO,
            flags = HslFlags.ACK_REQUIRED,
            sequence = sequence,
            payload = HslHelloCodec.encode(
                HslModuleHello(
                    contractMajor = contract.major,
                    contractMinor = contract.minor,
                    contractPatch = contract.patch,
                    capabilityMask = capabilityMask,
                    firmwareMajor = 1,
                    firmwareMinor = 2,
                    firmwarePatch = 3,
                    hardwareRevision = 1,
                    bootSessionId = 0x1020_3040,
                ),
            ),
        )
    }

    fun sendHeartbeat(sequence: Int = 2) {
        sendFrame(
            type = HslMessageType.HEARTBEAT,
            flags = 0,
            sequence = sequence,
            payload = HslPayloadCodec.encodeHeartbeat(
                HslHeartbeat(
                    uptimeMillis = 1_000,
                    operationalState = 2,
                    protocolVersion = HslFrameCodec.SUPPORTED_VERSION,
                    errorCount = 0,
                ),
            ),
        )
    }

    fun sendKey(sequence: Int = 3) {
        val payload = ByteArray(12).also { bytes ->
            bytes.putU32Le(0, 100)
            bytes[4] = 2
            bytes[5] = 3
            bytes.putU16Le(6, 120)
            bytes.putU32Le(8, 2_000)
        }
        sendFrame(HslMessageType.KEY_EVENT, HslFlags.ACK_REQUIRED, sequence, payload)
    }

    fun sendMma8452Sample(sequence: Int = 4) {
        sendFrame(
            type = HslMessageType.SENSOR_SAMPLE,
            flags = 0,
            sequence = sequence,
            payload = HslPayloadCodec.encodeSensorSample(
                HslSensorSample(
                    sampleReference = 200,
                    monotonicMillis = 2_100,
                    validFlags = 0x0001,
                    accelerationXMilliG = 10,
                    accelerationYMilliG = -20,
                    accelerationZMilliG = 1_000,
                    gyroXMilliDegreesPerSecond = 0,
                    gyroYMilliDegreesPerSecond = 0,
                    gyroZMilliDegreesPerSecond = 0,
                    electricFieldMilliVolts = 0,
                    pressurePascals = 0,
                    temperatureCentiCelsius = 0,
                    altitudeMillimetres = 0,
                ),
            ),
        )
    }

    fun sendRtkNmea(payload: ByteArray, sequence: Int = 5) {
        sendFrame(HslMessageType.RTK_NMEA, 0, sequence, payload)
    }

    suspend fun awaitOutbound(type: Int, timeoutMillis: Long = 5_000): HslFrame =
        withTimeout(timeoutMillis) { outboundFrames.receiveAsFlow().first { it.type == type } }

    private fun sendFrame(type: Int, flags: Int, sequence: Int, payload: ByteArray) {
        val encoded = HslFrameCodec.encode(HslFrame(flags = flags, type = type, sequence = sequence, payload = payload))
        synchronized(writes) { pty.writeFully(encoded) }
    }

    override fun close() {
        responder?.cancel()
        responder = null
        outboundFrames.close()
        pty.close()
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte() }
    }
}
