package com.example.helmet.communication.sync

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.CallStateTransitions
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.IceConfiguration
import com.example.helmet.core.model.IceServerConfig
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import com.example.helmet.feature.connectivity.HttpResponseTooLargeException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun callSignalPayloadsEquivalent(
    type: CallSignalType,
    left: JSONObject,
    right: JSONObject,
): Boolean = when (type) {
    CallSignalType.OFFER -> left.optString("sdp") == right.optString("sdp")
    CallSignalType.ANSWER ->
        left.optString("sdp") == right.optString("sdp") &&
            matchingPositiveOfferSequence(left, right)
    CallSignalType.ICE_CANDIDATE ->
        left.optString("sdpMid") == right.optString("sdpMid") &&
            left.optInt("sdpMLineIndex", -1) == right.optInt("sdpMLineIndex", -1) &&
            left.optString("candidate") == right.optString("candidate") &&
            matchingPositiveOfferSequence(left, right)
    CallSignalType.ICE_COMPLETE ->
        left.length() == 1 && right.length() == 1 && matchingPositiveOfferSequence(left, right)
}

private fun matchingPositiveOfferSequence(left: JSONObject, right: JSONObject): Boolean {
    val leftValue = left.opt("offerSequence")
    val rightValue = right.opt("offerSequence")
    if ((leftValue !is Int && leftValue !is Long) || (rightValue !is Int && rightValue !is Long)) return false
    val leftSequence = (leftValue as Number).toLong()
    val rightSequence = (rightValue as Number).toLong()
    return leftSequence > 0 && leftSequence == rightSequence
}

class HttpCommunicationClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : CommunicationTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun syncCall(call: CallSession): CallSyncReceipt = syncCall(call) {}

    suspend fun syncCall(
        call: CallSession,
        requestGate: () -> Unit,
    ): CallSyncReceipt = withContext(Dispatchers.IO) {
        syncCallWithRequest(call) { method, path, body ->
            requestGate()
            requestJson(method, path, body).also { requestGate() }
        }
    }

    suspend fun reconcileLegacyCall(
        call: CallSession,
        requestGate: () -> Unit = {},
    ): CallSyncReceipt = withContext(Dispatchers.IO) {
        reconcileLegacyCallWithRequest(call, wallClock) { method, path, body ->
            requestGate()
            requestJson(method, path, body).also { requestGate() }
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
                !callSignalPayloadsEquivalent(
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

    override suspend fun fetchCommandPage(
        deviceId: String,
        afterSequence: Long,
        limit: Int,
    ): DeviceCommandPage = withContext(Dispatchers.IO) {
        require(deviceId.isNotBlank())
        require(afterSequence >= 0)
        require(limit in 1..100)
        val response = requestJson(
            "GET",
            "/v1/device-commands?deviceId=${encode(deviceId)}&afterSequence=$afterSequence&limit=$limit",
            null,
        )
        parseDeviceCommandPage(response, deviceId, afterSequence, limit, wallClock())
    }

    override suspend fun acknowledgeCommand(
        commandStreamId: String,
        command: DeviceCommand,
        status: String,
        error: String?,
    ) {
        withContext(Dispatchers.IO) {
            requestJson(
                "POST",
                "/v1/device-commands/${encode(command.commandId)}/ack",
                deviceCommandAcknowledgementBody(commandStreamId, command, status, error),
            )
        }
    }

    override suspend fun sendBroadcastReceipt(
        commandStreamId: String,
        broadcastId: String,
        state: BroadcastPlaybackState,
        occurredAtEpochMillis: Long,
        error: String?,
    ) {
        withContext(Dispatchers.IO) {
            requestJson(
                "POST",
                "/v1/broadcasts/${encode(broadcastId)}/receipts",
                broadcastReceiptBody(
                    commandStreamId,
                    state,
                    occurredAtEpochMillis,
                    error,
                ),
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
            try {
                JSONObject(text)
            } catch (error: Exception) {
                throw CommunicationException("communication server returned invalid JSON", false, cause = error)
            }
        } catch (error: CommunicationException) {
            throw error
        } catch (error: IOException) {
            throw communicationIoFailure(error)
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
            var previous = afterSequence
            sequences.forEach { sequence ->
                if (previous == Long.MAX_VALUE || sequence != previous + 1L) {
                    throw CommunicationException("$responseName sequence contains a gap", retryable = false)
                }
                previous = sequence
            }
        }

        internal fun parseDeviceCommand(value: JSONObject, receivedAtEpochMillis: Long): DeviceCommand {
            try {
                val acknowledgedValue = value.opt("acknowledged")
                if (acknowledgedValue !is Boolean) {
                    throw CommunicationException(
                        "device command acknowledged flag is missing or invalid",
                        retryable = false,
                    )
                }
                return DeviceCommand(
                    commandId = value.getString("commandId").also {
                        if (it.isBlank()) throw IllegalArgumentException("command ID is blank")
                    },
                    deviceId = value.getString("deviceId").also {
                        if (it.isBlank()) throw IllegalArgumentException("command device ID is blank")
                    },
                    serverSequence = value.requireStrictLong("sequence").also {
                        if (it <= 0) throw IllegalArgumentException("command sequence is not positive")
                    },
                    type = DeviceCommandType.valueOf(value.getString("type")),
                    payloadJson = value.getJSONObject("payload").toString(),
                    createdAtEpochMillis = value.requireStrictLong("createdAtEpochMillis").also {
                        if (it <= 0) throw IllegalArgumentException("command creation time is not positive")
                    },
                    state = if (acknowledgedValue) {
                        DeviceCommandState.ACKNOWLEDGED
                    } else {
                        DeviceCommandState.RECEIVED
                    },
                    receivedAtEpochMillis = receivedAtEpochMillis,
                    appliedAtEpochMillis = null,
                    lastError = null,
                    ackDeliveryState = if (acknowledgedValue) DeliveryState.DELIVERED else DeliveryState.PENDING,
                    ackAttemptCount = 0,
                )
            } catch (error: CommunicationException) {
                throw error
            } catch (error: Exception) {
                throw CommunicationException("invalid device command response", retryable = false, cause = error)
            }
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

        internal fun requireCanonicalCommandStreamId(value: String): String {
            val parsed = runCatching { UUID.fromString(value) }.getOrNull()
            if (parsed == null || parsed.toString() != value) {
                throw CommunicationException("invalid command stream ID", retryable = false)
            }
            return value
        }

        internal fun parseDeviceCommandPage(
            response: JSONObject,
            deviceId: String,
            afterSequence: Long,
            limit: Int,
            receivedAtEpochMillis: Long,
        ): DeviceCommandPage {
            try {
                val commandStreamId = requireCanonicalCommandStreamId(response.optString("commandStreamId"))
                val commandValues = response.getJSONArray("commands")
                val commandHighWaterSequence = response.requireNonNegativeLong(
                    "commandHighWaterSequence",
                )
                if (commandHighWaterSequence < afterSequence) {
                    throw CommandStreamHighWaterRegressionException(
                        commandStreamId,
                        commandHighWaterSequence,
                        afterSequence,
                    )
                }
                if (commandValues.length() > limit) {
                    throw CommunicationException("device command response exceeds requested limit", retryable = false)
                }
                val commands = buildList {
                    for (index in 0 until commandValues.length()) {
                        add(parseDeviceCommand(commandValues.getJSONObject(index), receivedAtEpochMillis))
                    }
                }.also { parsed ->
                    if (parsed.any { it.deviceId != deviceId }) {
                        throw CommunicationException("command device mismatch", retryable = false)
                    }
                    validateResponseSequence(
                        "device command",
                        parsed.map(DeviceCommand::serverSequence),
                        afterSequence,
                        limit,
                    )
                }
                val hasMore = response.opt("hasMore") as? Boolean
                    ?: throw CommunicationException("device command hasMore is missing or invalid", retryable = false)
                val rawNext = response.opt("nextAfterSequence")
                val nextAfterSequence = when (rawNext) {
                    null, JSONObject.NULL -> null
                    is Int -> rawNext.toLong()
                    is Long -> rawNext
                    else -> throw CommunicationException(
                        "device command nextAfterSequence is invalid",
                        retryable = false,
                    )
                }
                val expectedNext = if (hasMore) commands.lastOrNull()?.serverSequence else null
                if (
                    nextAfterSequence != expectedNext ||
                    (hasMore && commands.isEmpty()) ||
                    (hasMore && nextAfterSequence == Long.MAX_VALUE)
                ) {
                    throw CommunicationException("device command page metadata mismatch", retryable = false)
                }
                val returnedCursor = commands.lastOrNull()?.serverSequence ?: afterSequence
                if (hasMore != (returnedCursor < commandHighWaterSequence)) {
                    throw CommunicationException("command page high-water metadata mismatch", retryable = false)
                }
                if (commands.lastOrNull()?.serverSequence?.let { it > commandHighWaterSequence } == true) {
                    throw CommunicationException("command page exceeds its high-water sequence", retryable = false)
                }
                return DeviceCommandPage(
                    commandStreamId,
                    commandHighWaterSequence,
                    commands,
                    hasMore,
                    nextAfterSequence,
                )
            } catch (error: CommunicationException) {
                throw error
            } catch (error: Exception) {
                throw CommunicationException("invalid device command page", retryable = false, cause = error)
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

internal fun communicationIoFailure(error: IOException): CommunicationException =
    if (error is HttpResponseTooLargeException) {
        CommunicationException("communication server response exceeds the size limit", false, cause = error)
    } else {
        CommunicationException("communication server I/O failure", true, cause = error)
    }

private fun JSONObject.requireNonNegativeLong(name: String): Long {
    val value = requireStrictLong(name)
    return value.also {
        if (it < 0) throw CommunicationException("device command $name is negative", retryable = false)
    }
}

private fun JSONObject.requireStrictLong(name: String): Long {
    val raw = opt(name)
    if (raw !is Int && raw !is Long) {
        throw CommunicationException("device command $name is invalid", retryable = false)
    }
    return (raw as Number).toLong()
}

internal fun deviceCommandAcknowledgementBody(
    commandStreamId: String,
    command: DeviceCommand,
    status: String,
    error: String?,
): JSONObject = JSONObject()
    .put("commandStreamId", HttpCommunicationClient.requireCanonicalCommandStreamId(commandStreamId))
    .put("status", status)
    .put("error", error ?: JSONObject.NULL)
    .put(
        "occurredAtEpochMillis",
        command.appliedAtEpochMillis ?: command.receivedAtEpochMillis,
    )

internal fun broadcastReceiptBody(
    commandStreamId: String,
    state: BroadcastPlaybackState,
    occurredAtEpochMillis: Long,
    error: String?,
): JSONObject = JSONObject()
    .put(
        "commandStreamId",
        HttpCommunicationClient.requireCanonicalCommandStreamId(commandStreamId),
    )
    .put("state", state.name)
    .put("occurredAtEpochMillis", occurredAtEpochMillis)
    .put("error", error ?: JSONObject.NULL)

internal fun syncCallWithRequest(
    call: CallSession,
    request: (method: String, path: String, body: JSONObject) -> JSONObject,
): CallSyncReceipt {
    if (call.direction == CallDirection.OUTGOING_DEVICE) {
        // An outgoing row can advance locally before its first network delivery. Always replay the
        // immutable REQUESTED/sequence-1 create first; the backend treats it idempotently even if it
        // has already advanced the call.
        val creation = parseCallReceipt(
            request("POST", "/v1/calls", outgoingCallCreationBody(call)),
        )
        if (
            creation.callId != call.callId ||
            creation.acknowledgedState != CallState.REQUESTED.name ||
            creation.acknowledgedStateSequence != 1L
        ) {
            throw CommunicationException(
                "call create response did not acknowledge the immutable initial state",
                retryable = true,
            )
        }
        if (call.state == CallState.REQUESTED && call.stateSequence == 1L) {
            return creation
        }
    }

    val transition = parseCallReceipt(
        request(
            "POST",
            "/v1/calls/${encodePathComponent(call.callId)}/transitions",
            JSONObject()
                .put("state", call.state.name)
                .put("actorId", call.deviceId)
                .put("occurredAtEpochMillis", call.updatedAtEpochMillis)
                .put("reason", call.lastReason ?: JSONObject.NULL),
        ),
    )
    return validateCallReceipt(call, transition)
}

internal fun validateCallReceipt(call: CallSession, receipt: CallSyncReceipt): CallSyncReceipt {
    if (
        receipt.callId != call.callId ||
        receipt.acknowledgedState != call.state.name ||
        receipt.acknowledgedStateSequence != call.stateSequence ||
        receipt.stateSequence < receipt.acknowledgedStateSequence
    ) {
        throw CommunicationException(
            "call server did not acknowledge the requested historical transition",
            retryable = true,
        )
    }
    return receipt
}

internal fun legacyCallReconciliationPath(from: CallState, target: CallState): List<CallState>? {
    if (from == target) return emptyList()
    val queue = ArrayDeque<List<CallState>>()
    queue.add(listOf(from))
    val visited = mutableSetOf(from)
    while (queue.isNotEmpty()) {
        val path = queue.removeFirst()
        val current = path.last()
        CallState.entries.forEach { candidate ->
            if (!CallStateTransitions.canTransition(current, candidate) || candidate == current) return@forEach
            val nextPath = path + candidate
            if (candidate == target) return nextPath.drop(1)
            if (visited.add(candidate)) queue.add(nextPath)
        }
    }
    return null
}

internal fun reconcileLegacyCallWithRequest(
    call: CallSession,
    wallClock: () -> Long,
    request: (method: String, path: String, body: JSONObject) -> JSONObject,
): CallSyncReceipt {
    require(call.direction == CallDirection.OUTGOING_DEVICE)
    var response = request("POST", "/v1/calls", outgoingCallCreationBody(call))
    var receipt = parseCallReceipt(response)
    var serverState = runCatching { CallState.valueOf(receipt.state) }
        .getOrElse { throw CommunicationException("invalid server call state", retryable = false) }
    if (serverState == call.state) {
        return receipt.copy(
            acknowledgedState = serverState.name,
            acknowledgedStateSequence = receipt.stateSequence,
            deduplicated = true,
        )
    }
    val path = legacyCallReconciliationPath(serverState, call.state)
        ?: return receipt.copy(
            acknowledgedState = serverState.name,
            acknowledgedStateSequence = receipt.stateSequence,
            deduplicated = true,
        )
    var occurredAt = maxOf(
        call.createdAtEpochMillis,
        call.updatedAtEpochMillis,
        receipt.updatedAtEpochMillis ?: 0L,
        wallClock(),
    )
    path.forEachIndexed { index, target ->
        if (receipt.stateSequence == Long.MAX_VALUE || occurredAt == Long.MAX_VALUE) {
            return receipt.copy(
                acknowledgedState = serverState.name,
                acknowledgedStateSequence = receipt.stateSequence,
                deduplicated = true,
            )
        }
        val expectedSequence = receipt.stateSequence + 1
        occurredAt += 1
        val reason = if (index == path.lastIndex) call.lastReason else "MIGRATED_V9_RECONCILIATION"
        response = request(
            "POST",
            "/v1/calls/${encodePathComponent(call.callId)}/transitions",
            JSONObject()
                .put("state", target.name)
                .put("actorId", call.deviceId)
                .put("occurredAtEpochMillis", occurredAt)
                .put("reason", reason ?: JSONObject.NULL),
        )
        receipt = parseCallReceipt(response)
        serverState = target
        val expected = call.copy(
            state = target,
            stateSequence = expectedSequence,
            updatedAtEpochMillis = occurredAt,
            lastReason = reason,
        )
        validateCallReceipt(expected, receipt)
    }
    return receipt
}

private fun outgoingCallCreationBody(call: CallSession): JSONObject = JSONObject()
    .put("callId", call.callId)
    .put("deviceId", call.deviceId)
    .put("direction", CallDirection.OUTGOING_DEVICE.name)
    .put("mediaMode", call.mediaMode.name)
    .put("state", CallState.REQUESTED.name)
    .put("stateSequence", 1)
    .put("relatedEventId", call.relatedEventId ?: JSONObject.NULL)
    .put("simulated", call.simulated)
    .put("createdAtEpochMillis", call.createdAtEpochMillis)

private fun parseCallReceipt(response: JSONObject): CallSyncReceipt = CallSyncReceipt(
    callId = response.getString("callId"),
    state = response.getString("state"),
    stateSequence = response.getLong("stateSequence"),
    acknowledgedState = response.getString("acknowledgedState"),
    acknowledgedStateSequence = response.getLong("acknowledgedStateSequence"),
    deduplicated = response.optBoolean("deduplicated", false),
    updatedAtEpochMillis = response.opt("updatedAtEpochMillis")?.let { raw ->
        (raw as? Number)?.toLong()?.takeIf { it > 0 }
    },
)

private fun encodePathComponent(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
