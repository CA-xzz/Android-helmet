package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslHeartbeat
import com.example.helmet.core.protocol.HslHelloCodec
import com.example.helmet.core.protocol.HslLocalIntercomStatus
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslModuleHello
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslSensorSample
import com.example.helmet.core.protocol.LocalIntercomCodec
import com.example.helmet.core.protocol.LocalIntercomModuleState
import com.example.helmet.core.protocol.LocalIntercomPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HslInboundValidationTest {
    @Test
    fun heartbeatReportsTheCurrentStableOperationalState() {
        HardwareOperationalState.entries.forEach { state ->
            val heartbeat = HslPayloadCodec.decodeHeartbeat(heartbeatPayload(123, state))
            assertEquals(state.wireValue, heartbeat.operationalState)
            assertEquals(123L, heartbeat.uptimeMillis)
        }
    }

    @Test
    fun ackValidationRejectsUnsupportedResultAndFlagMismatch() {
        val success = HslPayloadCodec.encodeAck(com.example.helmet.core.protocol.HslAck(1, 0))
        assertEquals(
            HSL_RESULT_SUCCESS,
            reliableFrameResult(frame(HslMessageType.ACK, success, flags = HslFlags.ACK)),
        )
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.ACK, success, flags = HslFlags.ACK or HslFlags.ERROR)),
        )
        val error = HslPayloadCodec.encodeAck(com.example.helmet.core.protocol.HslAck(1, 4))
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.ACK, error, flags = HslFlags.ACK)),
        )
        assertEquals(
            HSL_RESULT_SUCCESS,
            reliableFrameResult(frame(HslMessageType.ACK, error, flags = HslFlags.ACK or HslFlags.ERROR)),
        )
        val unsupported = HslPayloadCodec.encodeAck(com.example.helmet.core.protocol.HslAck(1, 5))
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.ACK, unsupported, flags = HslFlags.ACK or HslFlags.ERROR)),
        )
        assertEquals(
            1,
            validatedAcknowledgement(frame(HslMessageType.ACK, success, flags = HslFlags.ACK))
                ?.acknowledgedSequence,
        )
        assertEquals(
            4,
            validatedAcknowledgement(
                frame(HslMessageType.ACK, error, flags = HslFlags.ACK or HslFlags.ERROR),
            )?.resultCode,
        )
        assertNull(
            validatedAcknowledgement(
                frame(HslMessageType.ACK, success, flags = HslFlags.ACK, version = 2),
            ),
        )
        assertEquals(
            HardwareCommandOutcome.ACKNOWLEDGED,
            commandOutcomeForAcknowledgement(HslPayloadCodec.decodeAck(success)),
        )
        assertEquals(
            HardwareCommandOutcome.REJECTED,
            commandOutcomeForAcknowledgement(HslPayloadCodec.decodeAck(error)),
        )
        assertNull(
            validatedAcknowledgement(
                frame(HslMessageType.ACK, success, flags = HslFlags.ACK or 0x80),
            ),
        )
    }

    @Test
    fun moduleRestartDetectionAndPortRetryBackoffAreBounded() {
        assertFalse(remoteModuleRestarted(null, 1))
        assertFalse(remoteModuleRestarted(10, 10))
        assertFalse(remoteModuleRestarted(10, 11))
        assertTrue(remoteModuleRestarted(10, 1))

        assertEquals(250L, nextPortReopenDelayMillis(0))
        assertEquals(500L, nextPortReopenDelayMillis(250))
        assertEquals(30_000L, nextPortReopenDelayMillis(20_000))
        assertEquals(30_000L, nextPortReopenDelayMillis(30_000))
    }

    @Test
    fun helloTimeoutOnlyExpiresAnIncompleteHandshakeAtItsDeadline() {
        assertFalse(helloHandshakeTimedOut(null, 5_000, HardwareCompatibility.AWAITING_HELLO))
        assertFalse(helloHandshakeTimedOut(5_000, 4_999, HardwareCompatibility.AWAITING_HELLO))
        assertTrue(helloHandshakeTimedOut(5_000, 5_000, HardwareCompatibility.AWAITING_HELLO))
        assertFalse(helloHandshakeTimedOut(5_000, 5_001, HardwareCompatibility.COMPATIBLE))
    }

    @Test
    fun validatesHeartbeatKeySensorAndIntercomStatusBeforeSuccess() {
        val heartbeat = HslPayloadCodec.encodeHeartbeat(HslHeartbeat(1, 0, 1, 0))
        assertEquals(HSL_RESULT_SUCCESS, reliableFrameResult(frame(HslMessageType.HEARTBEAT, heartbeat)))
        assertEquals(
            HSL_RESULT_INVALID_LENGTH,
            reliableFrameResult(frame(HslMessageType.HEARTBEAT, heartbeat.copyOf(7))),
        )
        heartbeat[5] = 2
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.HEARTBEAT, heartbeat)),
        )

        val key = ByteArray(12).apply {
            this[0] = 1
            this[4] = 1
            this[5] = 3
        }
        assertEquals(HSL_RESULT_SUCCESS, reliableFrameResult(frame(HslMessageType.KEY_EVENT, key)))
        key[4] = 99.toByte()
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.KEY_EVENT, key)),
        )
        assertEquals(
            HSL_RESULT_INVALID_LENGTH,
            reliableFrameResult(frame(HslMessageType.KEY_EVENT, ByteArray(11))),
        )
        key[4] = 1
        key[5] = 1
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.KEY_EVENT, key)),
        )
        key[5] = 3
        key[0] = 0
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.KEY_EVENT, key)),
        )
        key[0] = 1
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.KEY_EVENT, key, flags = 0)),
        )

        val sensor = HslPayloadCodec.encodeSensorSample(sample())
        assertEquals(HSL_RESULT_SUCCESS, reliableFrameResult(frame(HslMessageType.SENSOR_SAMPLE, sensor)))
        sensor[9] = 0x80.toByte()
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.SENSOR_SAMPLE, sensor)),
        )
        assertEquals(
            HSL_RESULT_INVALID_LENGTH,
            reliableFrameResult(frame(HslMessageType.SENSOR_SAMPLE, ByteArray(39))),
        )

        val status = LocalIntercomPayloadCodec.encodeStatus(intercomStatus())
        assertEquals(
            HSL_RESULT_SUCCESS,
            reliableFrameResult(frame(HslMessageType.LOCAL_INTERCOM_STATUS, status)),
        )
        status[1] = 99.toByte()
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.LOCAL_INTERCOM_STATUS, status)),
        )
    }

    @Test
    fun rejectsReservedUnknownAndWrongDirectionReliableFrames() {
        listOf(
            HslMessageType.BATTERY_STATUS,
            HslMessageType.GET_CONFIG,
            HslMessageType.TIME_SYNC,
            HslMessageType.LOG_REQUEST,
            HslMessageType.LORA_MESSAGE,
            HslMessageType.RTK_STATUS,
            HslMessageType.SET_CONFIG,
            HslMessageType.SET_OUTPUT,
            HslMessageType.RTK_CORRECTION,
            HslMessageType.LOCAL_INTERCOM_COMMAND,
            0x7E,
        ).forEach { type ->
            assertEquals(
                "type=0x${type.toString(16)}",
                HSL_RESULT_UNSUPPORTED_TYPE,
                reliableFrameResult(frame(type, byteArrayOf())),
            )
        }
        assertEquals(
            HslFlags.ACK or HslFlags.ERROR,
            reliableAckFlags(HSL_RESULT_UNSUPPORTED_TYPE),
        )
    }

    @Test
    fun helloRequiresFixedPayloadAndReliableAcknowledgement() {
        val hello = HslHelloCodec.encode(
            HslModuleHello(
                contractMajor = 1,
                contractMinor = 0,
                contractPatch = 0,
                capabilityMask = com.example.helmet.core.protocol.HslCapability.KNOWN_MASK,
                firmwareMajor = 1,
                firmwareMinor = 2,
                firmwarePatch = 3,
                hardwareRevision = 1,
                bootSessionId = 9,
            ),
        )

        assertEquals(HSL_RESULT_SUCCESS, reliableFrameResult(frame(HslMessageType.HELLO, hello)))
        assertEquals(
            HSL_RESULT_INVALID_LENGTH,
            reliableFrameResult(frame(HslMessageType.HELLO, hello.copyOf(19))),
        )
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.HELLO, hello, flags = 0)),
        )
    }

    @Test
    fun streamedNmeaCannotRequestPrematureAcknowledgement() {
        assertEquals(
            HSL_RESULT_SUCCESS,
            reliableFrameResult(frame(HslMessageType.RTK_NMEA, "\$GNGGA,".encodeToByteArray(), flags = 0)),
        )
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(
                frame(
                    HslMessageType.RTK_NMEA,
                    "\$GNGGA,".encodeToByteArray(),
                    flags = HslFlags.ACK_REQUIRED,
                ),
            ),
        )
    }

    @Test
    fun rejectsUnsupportedVersionAndInvalidFlags() {
        val heartbeat = HslPayloadCodec.encodeHeartbeat(HslHeartbeat(1, 0, 1, 0))
        assertEquals(
            HSL_RESULT_UNSUPPORTED_VERSION,
            reliableFrameResult(frame(HslMessageType.HEARTBEAT, heartbeat, version = 2)),
        )
        assertEquals(
            HSL_RESULT_FIELD_OUT_OF_RANGE,
            reliableFrameResult(frame(HslMessageType.HEARTBEAT, heartbeat, flags = 0x80)),
        )
    }

    private fun frame(
        type: Int,
        payload: ByteArray,
        flags: Int = HslFlags.ACK_REQUIRED,
        version: Int = 1,
    ) = HslFrame(
        version = version,
        flags = flags,
        type = type,
        sequence = 7,
        payload = payload,
    )

    private fun sample() = HslSensorSample(
        sampleReference = 1,
        monotonicMillis = 1,
        validFlags = 0x001F,
        accelerationXMilliG = 0,
        accelerationYMilliG = 0,
        accelerationZMilliG = 1_000,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = 0,
        pressurePascals = 101_325,
        temperatureCentiCelsius = 2_000,
        altitudeMillimetres = 0,
    )

    private fun intercomStatus() = HslLocalIntercomStatus(
        state = LocalIntercomModuleState.READY,
        requestId = 1,
        peerCount = 0,
        codec = LocalIntercomCodec.MODULE_NEGOTIATED,
        sampleRateHertz = 0,
        rssiDbm = 0,
        snrTenthsDb = 0,
        packetLossPermille = 0,
        oneWayLatencyMillis = 0,
        transmittedPackets = 0,
        receivedPackets = 0,
        faultCode = 0,
    )
}
