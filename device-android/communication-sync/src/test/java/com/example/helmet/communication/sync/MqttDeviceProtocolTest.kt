package com.example.helmet.communication.sync

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MqttDeviceProtocolTest {
    @Test
    fun permanentlyOversizedQosOneDeliveryIsAcknowledgedInsteadOfPoisoningTheSession() {
        assertTrue(shouldAcknowledgeOversizedMqttDelivery(1))
        assertFalse(shouldAcknowledgeOversizedMqttDelivery(0))
        assertFalse(shouldAcknowledgeOversizedMqttDelivery(2))
    }

    @Test
    fun mqttCertificateAliasUsesTheProvisioningLimit() {
        listOf(128, 129, 256).forEach { length ->
            assertTrue(isValidMqttCertificateAlias("a".repeat(length)))
        }
        assertFalse(isValidMqttCertificateAlias("a".repeat(257)))
    }

    @Test
    fun inboundPayloadLimitAndQueueCapacityAreBoundedWithoutDroppingCommandWakeAcks() {
        val buffered = ArrayDeque<ByteArray>()
        fun offer(payload: ByteArray): Boolean {
            if (buffered.size >= MAX_BUFFERED_MQTT_INCOMING_MESSAGES) return false
            buffered.addLast(payload)
            return true
        }

        assertEquals(
            MqttIncomingAdmission.ACCEPTED,
            admitMqttIncomingPayload(
                commandWake = false,
                payload = ByteArray(MAX_MQTT_INCOMING_PAYLOAD_BYTES),
                qos = 1,
                offer = ::offer,
            ),
        )
        assertEquals(MAX_MQTT_INCOMING_PAYLOAD_BYTES, buffered.removeFirst().size)
        assertEquals(
            MqttIncomingAdmission.OVERSIZED_ACKNOWLEDGED,
            admitMqttIncomingPayload(
                commandWake = false,
                payload = ByteArray(MAX_MQTT_INCOMING_PAYLOAD_BYTES + 1),
                qos = 1,
                offer = ::offer,
            ),
        )
        assertTrue(buffered.isEmpty())

        repeat(MAX_BUFFERED_MQTT_INCOMING_MESSAGES) {
            assertEquals(
                MqttIncomingAdmission.ACCEPTED,
                admitMqttIncomingPayload(false, byteArrayOf(it.toByte()), qos = 1, offer = ::offer),
            )
        }
        assertEquals(
            MqttIncomingAdmission.QUEUE_FULL,
            admitMqttIncomingPayload(false, byteArrayOf(17), qos = 1, offer = ::offer),
        )
        assertEquals(MAX_BUFFERED_MQTT_INCOMING_MESSAGES, buffered.size)

        var commandPayloadSize = -1
        assertEquals(
            MqttIncomingAdmission.ACCEPTED,
            admitMqttIncomingPayload(
                commandWake = true,
                payload = ByteArray(MAX_MQTT_INCOMING_PAYLOAD_BYTES + 1),
                qos = 1,
            ) { payload ->
                commandPayloadSize = payload.size
                true
            },
        )
        assertEquals(0, commandPayloadSize)
    }

    @Test
    fun commandTopicIsAFormatIndependentBoundedWakeSignal() {
        val commandTopic = MqttDeviceProtocol.commandTopic("device-a")
        assertEquals(
            MqttInboundKind.COMMAND_WAKE,
            classifyMqttInbound(commandTopic, "device-a", qos = 1, retained = false),
        )
        assertEquals(
            MqttInboundKind.APPLICATION_RECEIPT,
            classifyMqttInbound(
                "helmet/v1/devices/device-a/down/result/message-1",
                "device-a",
                qos = 1,
                retained = false,
            ),
        )
        listOf(
            Triple(commandTopic, 0, false),
            Triple(commandTopic, 1, true),
            Triple("helmet/v1/devices/device-b/down/command", 1, false),
        ).forEach { (topic, qos, retained) ->
            assertThrows(MqttProtocolException::class.java) {
                classifyMqttInbound(topic, "device-a", qos, retained)
            }
        }
    }

    @Test
    fun malformedCommandPayloadStillWakesHttpAndWakeFailureRemainsUnacknowledged() = runBlocking {
        var wakeCount = 0
        processMqttIncomingDelivery(
            topic = MqttDeviceProtocol.commandTopic("device-a"),
            payload = byteArrayOf(0, 1, 2, 3),
            qos = 1,
            retained = false,
            deviceId = "device-a",
            onCommandWake = { wakeCount += 1 },
            onApplicationReceipt = { error("command wake must not parse a receipt") },
        )
        assertEquals(1, wakeCount)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                processMqttIncomingDelivery(
                    topic = MqttDeviceProtocol.commandTopic("device-a"),
                    payload = byteArrayOf(9),
                    qos = 1,
                    retained = false,
                    deviceId = "device-a",
                    onCommandWake = { error("scheduler unavailable") },
                    onApplicationReceipt = {},
                )
            }
        }
        Unit
    }

    @Test
    fun brokerUriRequiresTlsPortAndNoExtraComponents() {
        assertEquals(
            "ssl://mqtt.example.test:8883",
            MqttDeviceProtocol.normalizeBrokerUri("ssl://MQTT.EXAMPLE.TEST:8883"),
        )
        listOf(
            "tcp://mqtt.example.test:1883",
            "ssl://mqtt.example.test:1883",
            "ssl://user@mqtt.example.test:8883",
            "ssl://mqtt.example.test:8883/path",
        ).forEach { value ->
            assertThrows(MqttProtocolException::class.java) {
                MqttDeviceProtocol.normalizeBrokerUri(value)
            }
        }
    }

    @Test
    fun commandEnvelopeIsBoundToTopicDeviceMessageAndTime() {
        val parsed = MqttDeviceProtocol.parseCommand(
            "helmet/v1/devices/device-a/down/command",
            commandEnvelope().toString().toByteArray(),
            "device-a",
            1_786_000_000_100,
        )
        assertEquals("command-1", parsed.commandId)
        assertEquals(7, parsed.serverSequence)
        assertEquals(DeviceCommandType.TEXT_BROADCAST, parsed.type)
        assertEquals("broadcast-1", JSONObject(parsed.payloadJson).getString("broadcastId"))

        val wrongDevice = commandEnvelope().put("deviceId", "device-b")
        assertThrows(MqttProtocolException::class.java) {
            MqttDeviceProtocol.parseCommand(
                "helmet/v1/devices/device-a/down/command",
                wrongDevice.toString().toByteArray(),
                "device-a",
                1_786_000_000_100,
            )
        }
        val wrongTime = commandEnvelope().put("occurredAtEpochMillis", 1_786_000_000_001)
        assertThrows(MqttProtocolException::class.java) {
            MqttDeviceProtocol.parseCommand(
                "helmet/v1/devices/device-a/down/command",
                wrongTime.toString().toByteArray(),
                "device-a",
                1_786_000_000_100,
            )
        }
    }

    @Test
    fun commandSyncUsesUnifiedQosOneEnvelopeShape() {
        val sync = MqttDeviceProtocol.commandSync("device-a", 17, 1_786_000_000_000)
        assertEquals("helmet/v1/devices/device-a/up/command-sync", sync.topic)
        val value = JSONObject(sync.payload.toString(Charsets.UTF_8))
        assertEquals(1, value.getInt("schemaVersion"))
        assertEquals("command-sync", value.getString("kind"))
        assertEquals("device-a", value.getString("deviceId"))
        assertEquals(17, value.getJSONObject("payload").getLong("afterSequence"))
        assertTrue(sync.messageId.startsWith("sync-"))
    }

    @Test
    fun commandAckAndBroadcastReceiptHaveStableRetryIdentity() {
        val command = command()
        val first = MqttDeviceProtocol.commandAcknowledgement(
            "device-a",
            COMMAND_STREAM_ID,
            command,
            "APPLIED",
            null,
        )
        val retry = MqttDeviceProtocol.commandAcknowledgement(
            "device-a",
            COMMAND_STREAM_ID,
            command,
            "APPLIED",
            null,
        )
        assertEquals(first.messageId, retry.messageId)
        assertEquals(first.payload.toList(), retry.payload.toList())
        assertEquals("helmet/v1/devices/device-a/up/command-ack", first.topic)
        assertEquals(
            COMMAND_STREAM_ID,
            JSONObject(first.payload.toString(Charsets.UTF_8))
                .getJSONObject("payload")
                .getString("commandStreamId"),
        )

        val receipt = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            COMMAND_STREAM_ID,
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )
        val receiptRetry = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            COMMAND_STREAM_ID,
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )
        assertEquals(receipt.messageId, receiptRetry.messageId)
        assertEquals("helmet/v1/devices/device-a/up/broadcast-receipt", receipt.topic)
        assertEquals(
            COMMAND_STREAM_ID,
            JSONObject(receipt.payload.toString(Charsets.UTF_8))
                .getJSONObject("payload")
                .getString("commandStreamId"),
        )
    }

    @Test
    fun commandAckAndBroadcastReceiptRejectNonCanonicalCommandStreamIds() {
        listOf(
            "",
            "not-a-uuid",
            COMMAND_STREAM_ID.uppercase(),
            "{$COMMAND_STREAM_ID}",
        ).forEach { invalidCommandStreamId ->
            assertThrows(MqttProtocolException::class.java) {
                MqttDeviceProtocol.commandAcknowledgement(
                    "device-a",
                    invalidCommandStreamId,
                    command(),
                    "APPLIED",
                    null,
                )
            }
            assertThrows(MqttProtocolException::class.java) {
                MqttDeviceProtocol.broadcastReceipt(
                    "device-a",
                    invalidCommandStreamId,
                    "broadcast-1",
                    BroadcastPlaybackState.PLAYED,
                    1_786_000_000_200,
                    null,
                )
            }
        }
    }

    @Test
    fun retryIdentityIsScopedToCommandStream() {
        val command = command()
        val commandAck = MqttDeviceProtocol.commandAcknowledgement(
            "device-a",
            COMMAND_STREAM_ID,
            command,
            "APPLIED",
            null,
        )
        val otherStreamCommandAck = MqttDeviceProtocol.commandAcknowledgement(
            "device-a",
            OTHER_COMMAND_STREAM_ID,
            command,
            "APPLIED",
            null,
        )
        val receipt = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            COMMAND_STREAM_ID,
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )
        val otherStreamReceipt = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            OTHER_COMMAND_STREAM_ID,
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )

        assertFalse(commandAck.messageId == otherStreamCommandAck.messageId)
        assertFalse(receipt.messageId == otherStreamReceipt.messageId)
    }

    @Test
    fun applicationReceiptIsBoundToTopicAndRejectsIncompleteErrors() {
        val accepted = JSONObject()
            .put("schemaVersion", 1)
            .put("messageId", "ack-1")
            .put("deviceId", "device-a")
            .put("occurredAtEpochMillis", 1_786_000_000_000)
            .put("kind", "command-ack")
            .put("accepted", true)
            .put("result", JSONObject().put("commandId", "command-1"))
        val receipt = MqttDeviceProtocol.parseApplicationReceipt(
            "helmet/v1/devices/device-a/down/result/ack-1",
            accepted.toString().toByteArray(),
            "device-a",
        )
        assertTrue(receipt.accepted)
        assertEquals("command-1", receipt.result?.getString("commandId"))

        val rejected = JSONObject(accepted.toString()).put("accepted", false)
        rejected.remove("result")
        assertThrows(MqttProtocolException::class.java) {
            MqttDeviceProtocol.parseApplicationReceipt(
                "helmet/v1/devices/device-a/down/result/ack-1",
                rejected.toString().toByteArray(),
                "device-a",
            )
        }
        rejected.put("status", 409).put("error", "conflict")
        assertFalse(
            MqttDeviceProtocol.parseApplicationReceipt(
                "helmet/v1/devices/device-a/down/result/ack-1",
                rejected.toString().toByteArray(),
                "device-a",
            ).accepted,
        )
    }

    @Test
    fun certificateCommonNameParserRejectsUnsafeOrMissingIdentity() {
        assertEquals(
            "device-a",
            MqttCertificateIdentity.commonName("CN=device-a,O=Helmet\\, Inc,C=CN"),
        )
        assertEquals(null, MqttCertificateIdentity.commonName("O=Helmet,C=CN"))
        assertEquals(null, MqttCertificateIdentity.commonName("CN=../../device,O=Helmet"))
    }

    @Test
    fun acknowledgedCommandIsNeverAcceptedFromActiveMqttDelivery() {
        val envelope = commandEnvelope().also {
            it.getJSONObject("payload").put("acknowledged", true)
        }

        assertThrows(MqttProtocolException::class.java) {
            MqttDeviceProtocol.parseCommand(
                MqttDeviceProtocol.commandTopic("device-a"),
                envelope.toString().toByteArray(),
                "device-a",
                1_786_000_000_100,
            )
        }
    }

    private fun commandEnvelope(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("messageId", "command-1")
        .put("deviceId", "device-a")
        .put("occurredAtEpochMillis", 1_786_000_000_000)
        .put("kind", "command")
        .put(
            "payload",
            JSONObject()
                .put("commandId", "command-1")
                .put("deviceId", "device-a")
                .put("sequence", 7)
                .put("type", "TEXT_BROADCAST")
                .put("payload", JSONObject().put("broadcastId", "broadcast-1"))
                .put("createdAtEpochMillis", 1_786_000_000_000)
                .put("acknowledged", false),
        )

    private fun command() = DeviceCommand(
        commandId = "command-1",
        deviceId = "device-a",
        serverSequence = 7,
        type = DeviceCommandType.TEXT_BROADCAST,
        payloadJson = "{}",
        createdAtEpochMillis = 1_786_000_000_000,
        state = DeviceCommandState.APPLIED,
        receivedAtEpochMillis = 1_786_000_000_100,
        appliedAtEpochMillis = 1_786_000_000_200,
        lastError = null,
        ackDeliveryState = DeliveryState.PENDING,
        ackAttemptCount = 0,
    )

    private companion object {
        const val COMMAND_STREAM_ID = "018f0f9e-7b6c-7000-8000-000000000001"
        const val OTHER_COMMAND_STREAM_ID = "018f0f9e-7b6c-7000-8000-000000000002"
    }
}
