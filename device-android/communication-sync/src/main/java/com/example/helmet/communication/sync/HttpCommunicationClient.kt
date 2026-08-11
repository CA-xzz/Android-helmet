package com.example.helmet.communication.sync

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.IceConfiguration
import com.example.helmet.core.model.IceServerConfig
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpCommunicationClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : CommunicationTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun syncCall(call: CallSession): CallSyncReceipt = withContext(Dispatchers.IO) {
        val response = if (call.state == CallState.REQUESTED && call.stateSequence == 1L) {
            requestJson(
                "POST",
                "/v1/calls",
                JSONObject()
                    .put("callId", call.callId)
                    .put("deviceId", call.deviceId)
                    .put("direction", call.direction.name)
                    .put("mediaMode", call.mediaMode.name)
                    .put("state", call.state.name)
                    .put("stateSequence", call.stateSequence)
                    .put("relatedEventId", call.relatedEventId ?: JSONObject.NULL)
                    .put("simulated", call.simulated)
                    .put("createdAtEpochMillis", call.createdAtEpochMillis),
            )
        } else {
            requestJson(
                "POST",
                "/v1/calls/${encode(call.callId)}/transitions",
                JSONObject()
                    .put("state", call.state.name)
                    .put("actorId", call.deviceId)
                    .put("occurredAtEpochMillis", call.updatedAtEpochMillis)
                    .put("reason", call.lastReason ?: JSONObject.NULL),
            )
        }
        CallSyncReceipt(
            callId = response.getString("callId"),
            state = response.getString("state"),
            stateSequence = response.getLong("stateSequence"),
            deduplicated = response.optBoolean("deduplicated", false),
        ).also { receipt ->
            if (receipt.callId != call.callId || receipt.state != call.state.name) {
                throw CommunicationException("call server state does not match device state", retryable = true)
            }
        }
    }

    override suspend fun fetchIceConfiguration(
        callId: String,
        requesterId: String,
    ): IceConfiguration = withContext(Dispatchers.IO) {
        require(callId.isNotBlank())
        require(requesterId.isNotBlank())
        val response = requestJson(
            "GET",
            "/v1/calls/${encode(callId)}/ice-config?requesterId=${encode(requesterId)}",
            null,
        )
        val servers = response.getJSONArray("iceServers")
        IceConfiguration(
            callId = response.getString("callId"),
            requesterId = response.getString("requesterId"),
            servers = buildList {
                for (index in 0 until servers.length()) {
                    val value = servers.getJSONObject(index)
                    val urls = value.getJSONArray("urls")
                    add(
                        IceServerConfig(
                            urls = buildList {
                                for (urlIndex in 0 until urls.length()) add(urls.getString(urlIndex))
                            },
                            username = value.optString("username").takeIf(String::isNotBlank),
                            credential = value.optString("credential").takeIf(String::isNotBlank),
                        ),
                    )
                }
            },
            issuedAtEpochMillis = response.getLong("issuedAtEpochMillis"),
            expiresAtEpochMillis = response.getLong("expiresAtEpochMillis"),
        ).also { configuration ->
            if (configuration.callId != callId || configuration.requesterId != requesterId) {
                throw CommunicationException("ICE configuration identity mismatch", retryable = false)
            }
            if (!configuration.isUsableAt(wallClock())) {
                throw CommunicationException("ICE configuration is expired or too close to expiry", retryable = true)
            }
        }
    }

    override suspend fun sendCallSignal(signal: CallSignal): CallSignal = withContext(Dispatchers.IO) {
        val response = requestJson(
            "POST",
            "/v1/calls/${encode(signal.callId)}/signals",
            JSONObject()
                .put("signalId", signal.signalId)
                .put("senderId", signal.senderId)
                .put("type", signal.type.name)
                .put("payload", JSONObject(signal.payloadJson))
                .put("createdAtEpochMillis", signal.createdAtEpochMillis),
        )
        parseCallSignal(response).also { stored ->
            if (
                stored.signalId != signal.signalId || stored.callId != signal.callId ||
                stored.senderId != signal.senderId || stored.type != signal.type ||
                !payloadsEquivalent(
                    signal.type,
                    JSONObject(stored.payloadJson),
                    JSONObject(signal.payloadJson),
                )
            ) {
                throw CommunicationException("call signal server response mismatch", retryable = false)
            }
        }
    }

    override suspend fun fetchCallSignals(
        callId: String,
        afterSequence: Long,
        limit: Int,
    ): List<CallSignal> = withContext(Dispatchers.IO) {
        require(callId.isNotBlank())
        require(afterSequence >= 0)
        require(limit in 1..100)
        require(afterSequence <= Long.MAX_VALUE - limit)
        val response = requestJson(
            "GET",
            "/v1/calls/${encode(callId)}/signals?afterSequence=$afterSequence&limit=$limit",
            null,
        )
        val values = response.getJSONArray("signals")
        if (values.length() > limit) {
            throw CommunicationException("call signal response exceeds requested limit", retryable = false)
        }
        buildList {
            for (index in 0 until values.length()) add(parseCallSignal(values.getJSONObject(index)))
        }.also { signals ->
            require(signals.all { it.callId == callId }) { "call signal identity mismatch" }
            val sequences = signals.map { requireNotNull(it.serverSequence) }
            validateResponseSequence("call signal", sequences, afterSequence, limit)
        }
    }

    override suspend fun fetchCommands(
        deviceId: String,
        afterSequence: Long,
        limit: Int,
    ): List<DeviceCommand> = withContext(Dispatchers.IO) {
        require(deviceId.isNotBlank())
        require(afterSequence >= 0)
        require(limit in 1..100)
        require(afterSequence <= Long.MAX_VALUE - limit)
        val response = requestJson(
            "GET",
            "/v1/device-commands?deviceId=${encode(deviceId)}&afterSequence=$afterSequence&limit=$limit",
            null,
        )
        val commands = response.getJSONArray("commands")
        if (commands.length() > limit) {
            throw CommunicationException("device command response exceeds requested limit", retryable = false)
        }
        buildList {
            for (index in 0 until commands.length()) {
                val value = commands.getJSONObject(index)
                add(
                    DeviceCommand(
                        commandId = value.getString("commandId"),
                        deviceId = value.getString("deviceId"),
                        serverSequence = value.getLong("sequence"),
                        type = DeviceCommandType.valueOf(value.getString("type")),
                        payloadJson = value.getJSONObject("payload").toString(),
                        createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
                        state = DeviceCommandState.RECEIVED,
                        receivedAtEpochMillis = wallClock(),
                        appliedAtEpochMillis = null,
                        lastError = null,
                        ackDeliveryState = DeliveryState.PENDING,
                        ackAttemptCount = 0,
                    ),
                )
            }
        }.also { values ->
            require(values.all { it.deviceId == deviceId }) { "command device mismatch" }
            val sequences = values.map(DeviceCommand::serverSequence)
            validateResponseSequence("device command", sequences, afterSequence, limit)
        }
    }

    override suspend fun acknowledgeCommand(command: DeviceCommand, status: String, error: String?) {
        withContext(Dispatchers.IO) {
            requestJson(
                "POST",
                "/v1/device-commands/${encode(command.commandId)}/ack",
                JSONObject()
                    .put("status", status)
                    .put("error", error ?: JSONObject.NULL)
                    .put(
                        "occurredAtEpochMillis",
                        command.appliedAtEpochMillis ?: command.receivedAtEpochMillis,
                    ),
            )
        }
    }

    override suspend fun sendBroadcastReceipt(
        broadcastId: String,
        state: BroadcastPlaybackState,
        occurredAtEpochMillis: Long,
        error: String?,
    ) {
        withContext(Dispatchers.IO) {
            requestJson(
                "POST",
                "/v1/broadcasts/${encode(broadcastId)}/receipts",
                JSONObject()
                    .put("state", state.name)
                    .put("occurredAtEpochMillis", occurredAtEpochMillis)
                    .put("error", error ?: JSONObject.NULL),
            )
        }
    }

    private fun requestJson(method: String, path: String, body: JSONObject?): JSONObject {
        val bodyBytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val connection = HttpConnectionPolicy.apply(
            URL("$endpoint$path").openConnection() as HttpURLConnection,
        ).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            if (bodyBytes != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(bodyBytes.size)
            }
        }
        return try {
            if (bodyBytes != null) connection.outputStream.use { it.write(bodyBytes) }
            val status = connection.responseCode
            val text = HttpConnectionPolicy.readUtf8Response(
                connection,
                status,
                MAX_RESPONSE_BYTES,
            )
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf(String::isNotBlank) ?: text.take(1_024)
                throw CommunicationException(
                    "communication server HTTP $status: $detail",
                    retryable = status == 408 || status == 429 || status >= 500,
                    statusCode = status,
                )
            }
            JSONObject(text)
        } catch (error: CommunicationException) {
            throw error
        } catch (error: IOException) {
            throw CommunicationException("communication server I/O failure", true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun validateResponseSequence(
            responseName: String,
            sequences: List<Long>,
            afterSequence: Long,
            requestedLimit: Int,
        ) {
            if (sequences.size > requestedLimit) {
                throw CommunicationException("$responseName response exceeds requested limit", retryable = false)
            }
            val expected = List(sequences.size) { index -> afterSequence + index + 1L }
            if (sequences != expected) {
                throw CommunicationException("$responseName sequence contains a gap", retryable = true)
            }
        }

        private fun payloadsEquivalent(type: CallSignalType, left: JSONObject, right: JSONObject): Boolean =
            when (type) {
                CallSignalType.OFFER, CallSignalType.ANSWER ->
                    left.optString("sdp") == right.optString("sdp")
                CallSignalType.ICE_CANDIDATE ->
                    left.optString("sdpMid") == right.optString("sdpMid") &&
                        left.optInt("sdpMLineIndex", -1) == right.optInt("sdpMLineIndex", -1) &&
                        left.optString("candidate") == right.optString("candidate")
                CallSignalType.ICE_COMPLETE -> left.length() == 0 && right.length() == 0
            }

        private fun parseCallSignal(value: JSONObject): CallSignal = CallSignal(
            signalId = value.getString("signalId"),
            callId = value.getString("callId"),
            serverSequence = value.getLong("sequence"),
            senderId = value.getString("senderId"),
            type = CallSignalType.valueOf(value.getString("type")),
            payloadJson = value.getJSONObject("payload").toString(),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
        )

        internal fun validateAndNormalizeBaseUrl(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrElse {
                throw IllegalArgumentException("invalid backend base URL", it)
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "backend base URL must not contain a path" }
            require(uri.query == null && uri.fragment == null && uri.userInfo == null)
            val host = uri.host?.lowercase().orEmpty()
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
                "backend base URL must use HTTPS; HTTP is allowed only for loopback tests"
            }
            require(host.isNotBlank())
            return raw.trim().trimEnd('/')
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
