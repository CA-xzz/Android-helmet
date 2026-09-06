package com.example.helmet.communication.sync

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class MqttUplink(
    val messageId: String,
    val topic: String,
    val payload: ByteArray,
)

data class MqttApplicationReceipt(
    val messageId: String,
    val accepted: Boolean,
    val status: Int?,
    val error: String?,
    val result: JSONObject?,
)

class MqttProtocolException(message: String) : IllegalArgumentException(message)

object MqttDeviceProtocol {
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private val idPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun normalizeBrokerUri(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw MqttProtocolException("invalid MQTT broker URI") }
        if (
            uri.scheme?.lowercase(Locale.US) != "ssl" || uri.host.isNullOrBlank() ||
            uri.port != 8_883 || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || !uri.rawPath.isNullOrEmpty()
        ) {
            throw MqttProtocolException("MQTT broker URI must use ssl://host:8883 without extra components")
        }
        return "ssl://${uri.host.lowercase(Locale.US)}:${uri.port}"
    }

    fun commandTopic(deviceId: String): String =
        "helmet/v1/devices/${validatedId(deviceId, "device ID")}/down/command"

    fun resultFilter(deviceId: String): String =
        "helmet/v1/devices/${validatedId(deviceId, "device ID")}/down/result/+"

    fun parseCommand(
        topic: String,
        bytes: ByteArray,
        expectedDeviceId: String,
        receivedAtEpochMillis: Long,
    ): DeviceCommand {
        if (topic != commandTopic(expectedDeviceId)) throw MqttProtocolException("command topic mismatch")
        val envelope = parseObject(bytes)
        requireSchemaAndDevice(envelope, expectedDeviceId)
        if (envelope.optString("kind") != "command") throw MqttProtocolException("command kind mismatch")
        val payload = requiredObject(envelope, "payload")
        val commandId = requiredId(payload, "commandId")
        if (requiredId(envelope, "messageId") != commandId) {
            throw MqttProtocolException("command message ID mismatch")
        }
        if (payload.optString("deviceId") != expectedDeviceId) {
            throw MqttProtocolException("command payload device mismatch")
        }
        if (payload.opt("acknowledged") != false) {
            throw MqttProtocolException("MQTT must not redeliver an acknowledged command")
        }
        val sequence = requiredPositiveLong(payload, "sequence")
        val createdAt = requiredPositiveLong(payload, "createdAtEpochMillis")
        if (requiredPositiveLong(envelope, "occurredAtEpochMillis") != createdAt) {
            throw MqttProtocolException("command time mismatch")
        }
        val type = runCatching { DeviceCommandType.valueOf(payload.getString("type")) }
            .getOrElse { throw MqttProtocolException("unsupported command type") }
        return DeviceCommand(
            commandId = commandId,
            deviceId = expectedDeviceId,
            serverSequence = sequence,
            type = type,
            payloadJson = requiredObject(payload, "payload").toString(),
            createdAtEpochMillis = createdAt,
            state = DeviceCommandState.RECEIVED,
            receivedAtEpochMillis = receivedAtEpochMillis,
            appliedAtEpochMillis = null,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )
    }

    fun parseApplicationReceipt(
        topic: String,
        bytes: ByteArray,
        expectedDeviceId: String,
    ): MqttApplicationReceipt {
        val prefix = "helmet/v1/devices/${validatedId(expectedDeviceId, "device ID")}/down/result/"
        if (!topic.startsWith(prefix)) throw MqttProtocolException("result topic mismatch")
        val topicMessageId = topic.removePrefix(prefix)
        validatedId(topicMessageId, "result topic message ID")
        val value = parseObject(bytes)
        requireSchemaAndDevice(value, expectedDeviceId)
        val messageId = requiredId(value, "messageId")
        if (messageId != topicMessageId) throw MqttProtocolException("result message ID mismatch")
        if (!value.has("accepted") || value.opt("accepted") !is Boolean) {
            throw MqttProtocolException("result accepted flag is invalid")
        }
        val accepted = value.getBoolean("accepted")
        val rawStatus = value.opt("status")
        val status = when (rawStatus) {
            null, JSONObject.NULL -> null
            is Int -> rawStatus
            is Long -> rawStatus.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                ?: throw MqttProtocolException("result status is invalid")
            else -> throw MqttProtocolException("result status is invalid")
        }
        val error = value.optString("error").takeIf(String::isNotBlank)
        val result = value.optJSONObject("result")
        if (accepted && result == null) throw MqttProtocolException("accepted result body is missing")
        if (!accepted && (status == null || status !in 400..599 || error == null)) {
            throw MqttProtocolException("rejected result detail is invalid")
        }
        return MqttApplicationReceipt(messageId, accepted, status, error, result)
    }

    fun commandSync(deviceId: String, afterSequence: Long, occurredAtEpochMillis: Long): MqttUplink {
        require(afterSequence >= 0) { "command sync sequence must be non-negative" }
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("afterSequence", afterSequence)
            .put("occurredAtEpochMillis", occurredAtEpochMillis)
        return uplink(
            deviceId,
            "command-sync",
            "sync-${UUID.randomUUID()}",
            occurredAtEpochMillis,
            payload,
        )
    }

    fun commandAcknowledgement(
        deviceId: String,
        commandStreamId: String,
        command: DeviceCommand,
        status: String,
        error: String?,
    ): MqttUplink {
        require(command.deviceId == deviceId) { "command device mismatch" }
        require(status == "APPLIED" || status == "FAILED") { "command status is invalid" }
        val safeCommandStreamId = validatedCommandStreamId(commandStreamId)
        val occurredAt = command.appliedAtEpochMillis ?: command.receivedAtEpochMillis
        val payload = JSONObject()
            .put("commandStreamId", safeCommandStreamId)
            .put("commandId", command.commandId)
            .put("status", status)
            .put("error", error ?: JSONObject.NULL)
            .put("occurredAtEpochMillis", occurredAt)
        return uplink(
            deviceId,
            "command-ack",
            stableMessageId(
                "ack",
                "$safeCommandStreamId|${command.commandId}|$status|$occurredAt|${error.orEmpty()}",
            ),
            occurredAt,
            payload,
        )
    }

    fun broadcastReceipt(
        deviceId: String,
        commandStreamId: String,
        broadcastId: String,
        state: BroadcastPlaybackState,
        occurredAtEpochMillis: Long,
        error: String?,
    ): MqttUplink {
        val safeCommandStreamId = validatedCommandStreamId(commandStreamId)
        validatedId(broadcastId, "broadcast ID")
        val payload = JSONObject()
            .put("commandStreamId", safeCommandStreamId)
            .put("broadcastId", broadcastId)
            .put("state", state.name)
            .put("occurredAtEpochMillis", occurredAtEpochMillis)
            .put("error", error ?: JSONObject.NULL)
        return uplink(
            deviceId,
            "broadcast-receipt",
            stableMessageId(
                "receipt",
                "$safeCommandStreamId|$broadcastId|${state.name}|$occurredAtEpochMillis|${error.orEmpty()}",
            ),
            occurredAtEpochMillis,
            payload,
        )
    }

    private fun uplink(
        deviceId: String,
        kind: String,
        messageId: String,
        occurredAtEpochMillis: Long,
        payload: JSONObject,
    ): MqttUplink {
        val safeDeviceId = validatedId(deviceId, "device ID")
        validatedId(messageId, "message ID")
        require(occurredAtEpochMillis > 0) { "occurred time must be positive" }
        val bytes = JSONObject()
            .put("schemaVersion", 1)
            .put("messageId", messageId)
            .put("deviceId", safeDeviceId)
            .put("occurredAtEpochMillis", occurredAtEpochMillis)
            .put("kind", kind)
            .put("payload", payload)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "MQTT payload exceeds one MiB" }
        return MqttUplink(
            messageId = messageId,
            topic = "helmet/v1/devices/$safeDeviceId/up/$kind",
            payload = bytes,
        )
    }

    private fun parseObject(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) {
            throw MqttProtocolException("MQTT payload size is invalid")
        }
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw MqttProtocolException("MQTT payload is not valid JSON") }
    }

    private fun requireSchemaAndDevice(value: JSONObject, expectedDeviceId: String) {
        val schemaVersion = value.opt("schemaVersion")
        if (schemaVersion != 1 && schemaVersion != 1L) throw MqttProtocolException("schema is invalid")
        if (value.optString("deviceId") != expectedDeviceId) {
            throw MqttProtocolException("message device mismatch")
        }
        requiredPositiveLong(value, "occurredAtEpochMillis")
    }

    private fun requiredObject(value: JSONObject, name: String): JSONObject =
        value.optJSONObject(name) ?: throw MqttProtocolException("$name must be an object")

    private fun requiredId(value: JSONObject, name: String): String =
        validatedId(value.optString(name), name)

    private fun validatedId(value: String, name: String): String = value.also {
        if (!idPattern.matches(it)) throw MqttProtocolException("$name is invalid")
    }

    private fun validatedCommandStreamId(value: String): String = value.also {
        val parsed = runCatching { UUID.fromString(it) }.getOrNull()
        if (parsed == null || parsed.toString() != it) {
            throw MqttProtocolException("command stream ID is invalid")
        }
    }

    private fun requiredPositiveLong(value: JSONObject, name: String): Long {
        val raw = value.opt(name)
        if (raw !is Number || raw is Double || raw is Float) {
            throw MqttProtocolException("$name is invalid")
        }
        return raw.toLong().takeIf { it > 0 } ?: throw MqttProtocolException("$name is invalid")
    }

    private fun stableMessageId(prefix: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return "$prefix-${digest.joinToString("") { byte -> "%02x".format(byte) }}"
    }
}
