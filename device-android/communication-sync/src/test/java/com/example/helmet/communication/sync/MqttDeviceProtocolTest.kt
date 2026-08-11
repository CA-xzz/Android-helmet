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

class MqttDeviceProtocolTest {
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
        val first = MqttDeviceProtocol.commandAcknowledgement("device-a", command, "APPLIED", null)
        val retry = MqttDeviceProtocol.commandAcknowledgement("device-a", command, "APPLIED", null)
        assertEquals(first.messageId, retry.messageId)
        assertEquals(first.payload.toList(), retry.payload.toList())
        assertEquals("helmet/v1/devices/device-a/up/command-ack", first.topic)

        val receipt = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )
        val receiptRetry = MqttDeviceProtocol.broadcastReceipt(
            "device-a",
            "broadcast-1",
            BroadcastPlaybackState.PLAYED,
            1_786_000_000_200,
            null,
        )
        assertEquals(receipt.messageId, receiptRetry.messageId)
        assertEquals("helmet/v1/devices/device-a/up/broadcast-receipt", receipt.topic)
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
}
