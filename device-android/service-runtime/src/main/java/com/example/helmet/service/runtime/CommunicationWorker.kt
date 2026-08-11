package com.example.helmet.service.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.helmet.communication.sync.CommunicationException
import com.example.helmet.communication.sync.CommunicationTransport
import com.example.helmet.communication.sync.DeviceMessageTransport
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.communication.sync.MqttDeviceSession
import com.example.helmet.communication.sync.MqttDeviceSessionRegistry
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.TextBroadcast
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal fun isLoopbackBackendUrl(value: String): Boolean = runCatching {
    URI(value).host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")
}.getOrDefault(false)

internal fun requiredNetworkTypeForBackendUrl(value: String): NetworkType =
    if (isLoopbackBackendUrl(value)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED

internal fun workPolicyForContinuation(continuation: Boolean): ExistingWorkPolicy =
    if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP

internal data class BackendWorkRoute(
    val activeName: String,
    val staleName: String,
    val requiredNetworkType: NetworkType,
)

internal fun backendWorkRoute(baseName: String, backendUrl: String): BackendWorkRoute =
    if (isLoopbackBackendUrl(backendUrl)) {
        BackendWorkRoute(
            activeName = "$baseName-loopback-v2",
            staleName = "$baseName-network-v2",
            requiredNetworkType = requiredNetworkTypeForBackendUrl(backendUrl),
        )
    } else {
        BackendWorkRoute(
            activeName = "$baseName-network-v2",
            staleName = "$baseName-loopback-v2",
            requiredNetworkType = requiredNetworkTypeForBackendUrl(backendUrl),
        )
    }

internal fun nextBroadcastEventTime(candidateEpochMillis: Long, previousEpochMillis: Long): Long {
    require(previousEpochMillis < Long.MAX_VALUE) { "broadcast event time exhausted" }
    return maxOf(candidateEpochMillis, previousEpochMillis + 1)
}

internal data class BroadcastReceiptReplay(
    val state: BroadcastPlaybackState,
    val occurredAtEpochMillis: Long,
    val error: String?,
)

internal fun broadcastReceiptReplay(broadcast: TextBroadcast): List<BroadcastReceiptReplay> = buildList {
    add(
        BroadcastReceiptReplay(
            BroadcastPlaybackState.RECEIVED,
            broadcast.receivedAtEpochMillis,
            null,
        ),
    )
    if (broadcast.playbackState == BroadcastPlaybackState.RECEIVED) return@buildList
    broadcast.playingAtEpochMillis?.let { playingAt ->
        add(BroadcastReceiptReplay(BroadcastPlaybackState.PLAYING, playingAt, null))
    }
    if (broadcast.playbackState == BroadcastPlaybackState.PLAYING) {
        require(broadcast.playingAtEpochMillis != null) { "playing broadcast is missing its event time" }
        return@buildList
    }
    add(
        BroadcastReceiptReplay(
            broadcast.playbackState,
            requireNotNull(broadcast.playedAtEpochMillis) { "completed broadcast is missing its event time" },
            broadcast.lastError,
        ),
    )
}

class CommunicationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return Result.success()
        val wallClock = DeviceTimeAuthorityProvider.get(applicationContext)::nowEpochMillis
        val httpClient = runCatching {
            HttpCommunicationClient(config.backendBaseUrl, config.backendBearerToken, wallClock = wallClock)
        }.getOrElse { return Result.failure() }
        val database = HelmetDatabase.get(applicationContext)
        val deviceId = DeviceIdentityStore(applicationContext).getOrCreateDeviceId()
        val calls = CallStore(database, wallClock)
        val broadcasts = BroadcastStore(database)
        val commands = DeviceCommandStore(database)
        val events = EventStore(database, wallClock)

        val initialCallResult = syncPendingCalls(httpClient, calls, events, wallClock)
        if (initialCallResult != null) return initialCallResult
        val mqttConfigured = config.mqttBrokerUri.isNotBlank() || config.mqttClientCertificateAlias.isNotBlank()
        if (mqttConfigured && (
                config.mqttBrokerUri.isBlank() || config.mqttClientCertificateAlias.isBlank()
            )
        ) {
            return Result.failure()
        }
        val messageTransport: DeviceMessageTransport = if (mqttConfigured) {
            MqttDeviceSessionRegistry.ready(deviceId) ?: return Result.retry()
        } else {
            httpClient
        }
        var fetchedFullHttpCommandPage = false
        try {
            val after = commands.maxSequence(deviceId)
            if (messageTransport is MqttDeviceSession) {
                messageTransport.synchronizeCommands(after)
            } else {
                val fetched = httpClient.fetchCommands(deviceId, after, MAX_COMMANDS_PER_RUN)
                fetched.forEach { command -> commands.receive(command) }
                fetchedFullHttpCommandPage = fetched.size == MAX_COMMANDS_PER_RUN
            }
        } catch (error: CommunicationException) {
            return if (error.retryable) Result.retry() else Result.failure()
        } catch (error: Throwable) {
            return Result.retry()
        }

        val pendingAckResult = syncPendingCommandAcks(messageTransport, commands, wallClock)
        if (pendingAckResult != null) return pendingAckResult
        val pendingBroadcastReceiptResult = syncPendingBroadcastReceipts(
            messageTransport,
            broadcasts,
            wallClock,
        )
        if (pendingBroadcastReceiptResult != null) return pendingBroadcastReceiptResult

        AndroidTextPlayback(applicationContext).use { playback ->
            for (command in commands.pendingApplication(MAX_COMMANDS_PER_RUN)) {
                val result = processCommand(
                    command,
                    messageTransport,
                    calls,
                    broadcasts,
                    commands,
                    events,
                    playback,
                    wallClock,
                )
                if (result != null) return result
            }
        }
        val finalCallResult = syncPendingCalls(httpClient, calls, events, wallClock)
        if (finalCallResult != null) return finalCallResult
        if (
            fetchedFullHttpCommandPage ||
            calls.pendingCount() > 0 ||
            commands.pendingApplicationCount() > 0 ||
            commands.pendingAckCount() > 0 ||
            broadcasts.pendingReceiptCount() > 0
        ) {
            enqueue(applicationContext, continuation = true)
        }
        return Result.success()
    }

    private suspend fun syncPendingCalls(
        client: CommunicationTransport,
        calls: CallStore,
        events: EventStore,
        wallClock: () -> Long,
    ): Result? {
        for (call in calls.pending(MAX_CALLS_PER_RUN)) {
            calls.markAttempt(call.callId, wallClock())
            try {
                val receipt = client.syncCall(call)
                calls.markDelivered(call.callId, wallClock())
                events.record(
                    eventType = "CALL_STATE_SYNCED",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "callId" to call.callId,
                            "state" to call.state.name,
                            "stateSequence" to receipt.stateSequence,
                            "deduplicated" to receipt.deduplicated,
                        ),
                    ).toString(),
                )
            } catch (error: CommunicationException) {
                calls.markFailed(call.callId, error.toString())
                return if (error.retryable) Result.retry() else Result.failure()
            } catch (error: Throwable) {
                calls.markFailed(call.callId, error.toString())
                return Result.retry()
            }
        }
        return null
    }

    private suspend fun processCommand(
        command: DeviceCommand,
        messageTransport: DeviceMessageTransport,
        calls: CallStore,
        broadcasts: BroadcastStore,
        commands: DeviceCommandStore,
        events: EventStore,
        playback: TextPlayback,
        wallClock: () -> Long,
    ): Result? = try {
        when (command.type) {
            DeviceCommandType.CALL_STATE -> processCallCommand(command, calls, commands, events, wallClock)
            DeviceCommandType.TEXT_BROADCAST ->
                processBroadcastCommand(
                    command,
                    messageTransport,
                    broadcasts,
                    commands,
                    events,
                    playback,
                    wallClock,
                )
        }
        acknowledgeAppliedCommand(messageTransport, commands, command, wallClock)
        null
    } catch (error: CommunicationException) {
        commands.markAckFailed(command.commandId, error.toString())
        if (error.retryable) Result.retry() else Result.failure()
    } catch (error: Throwable) {
        val now = wallClock()
        commands.markFailed(command.commandId, now, error.toString())
        val stored = requireNotNull(commands.find(command.commandId))
        commands.markAckAttempt(command.commandId, now)
        val acknowledgementResult = try {
            messageTransport.acknowledgeCommand(
                stored,
                "FAILED",
                stored.lastError?.take(MAX_ERROR_LENGTH),
            )
            commands.markAckDelivered(command.commandId, wallClock())
            null
        } catch (ackError: CommunicationException) {
            commands.markAckFailed(command.commandId, ackError.toString())
            if (ackError.retryable) Result.retry() else Result.failure()
        } catch (ackError: Throwable) {
            commands.markAckFailed(command.commandId, ackError.toString())
            Result.retry()
        }
        events.record(
            eventType = "DEVICE_COMMAND_FAILED",
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf("commandId" to command.commandId, "type" to command.type.name, "error" to error.toString()),
            ).toString(),
        )
        acknowledgementResult
    }

    private suspend fun processCallCommand(
        command: DeviceCommand,
        calls: CallStore,
        commands: DeviceCommandStore,
        events: EventStore,
        wallClock: () -> Long,
    ) {
        val payload = JSONObject(command.payloadJson)
        val callId = payload.getString("callId")
        val target = CallState.valueOf(payload.getString("state"))
        calls.transition(callId, target, payload.optString("reason").takeIf(String::isNotBlank))
        commands.markApplied(command.commandId, wallClock())
        events.record(
            eventType = "CALL_STATE_APPLIED",
            severity = EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf("commandId" to command.commandId, "callId" to callId, "state" to target.name),
            ).toString(),
        )
        ContextCompat.startForegroundService(
            applicationContext,
            HelmetService.callStateIntent(applicationContext, callId, target),
        )
    }

    private suspend fun processBroadcastCommand(
        command: DeviceCommand,
        client: DeviceMessageTransport,
        broadcasts: BroadcastStore,
        commands: DeviceCommandStore,
        events: EventStore,
        playback: TextPlayback,
        wallClock: () -> Long,
    ) {
        val payload = JSONObject(command.payloadJson)
        val receivedAt = nextBroadcastEventTime(wallClock(), command.createdAtEpochMillis)
        val broadcastId = payload.getString("broadcastId")
        val message = TextBroadcast(
            broadcastId = broadcastId,
            deviceId = command.deviceId,
            serverSequence = command.serverSequence,
            text = payload.getString("text"),
            language = payload.getString("language"),
            priority = payload.getInt("priority"),
            expiresAtEpochMillis = payload.optLong("expiresAtEpochMillis").takeIf { !payload.isNull("expiresAtEpochMillis") },
            playbackState = BroadcastPlaybackState.RECEIVED,
            receivedAtEpochMillis = receivedAt,
            playingAtEpochMillis = null,
            playedAtEpochMillis = null,
            lastError = null,
            receiptDeliveryState = DeliveryState.PENDING,
            receiptAttemptCount = 0,
        )
        broadcasts.receive(message)
        var stored = requireNotNull(broadcasts.find(broadcastId))
        sendReceipt(
            client,
            broadcasts,
            broadcastId,
            BroadcastPlaybackState.RECEIVED,
            stored.receivedAtEpochMillis,
            null,
            wallClock,
        )
        if (stored.playbackState == BroadcastPlaybackState.PLAYED ||
            stored.playbackState == BroadcastPlaybackState.FAILED ||
            stored.playbackState == BroadcastPlaybackState.EXPIRED
        ) {
            val completedAt = requireNotNull(stored.playedAtEpochMillis)
            sendReceipt(client, broadcasts, broadcastId, stored.playbackState, completedAt, stored.lastError, wallClock)
            commands.markApplied(command.commandId, completedAt)
            return
        }
        val expiresAt = stored.expiresAtEpochMillis
        val finalState: BroadcastPlaybackState
        val finalError: String?
        var latestEventAt = receivedAt
        if (expiresAt != null && receivedAt >= expiresAt) {
            finalState = BroadcastPlaybackState.EXPIRED
            finalError = "BROADCAST_EXPIRED"
        } else {
            val playingAt = stored.playingAtEpochMillis
                ?: nextBroadcastEventTime(wallClock(), stored.receivedAtEpochMillis)
            broadcasts.updatePlayback(broadcastId, BroadcastPlaybackState.PLAYING, playingAt)
            sendReceipt(client, broadcasts, broadcastId, BroadcastPlaybackState.PLAYING, playingAt, null, wallClock)
            latestEventAt = playingAt
            stored = requireNotNull(broadcasts.find(broadcastId))
            val outcome = playback.play(stored.text, stored.language, "broadcast-$broadcastId")
            finalState = if (outcome.success) BroadcastPlaybackState.PLAYED else BroadcastPlaybackState.FAILED
            finalError = outcome.error
        }
        val completedAt = nextBroadcastEventTime(wallClock(), latestEventAt)
        broadcasts.updatePlayback(broadcastId, finalState, completedAt, finalError)
        sendReceipt(client, broadcasts, broadcastId, finalState, completedAt, finalError, wallClock)
        commands.markApplied(command.commandId, completedAt)
        events.record(
            eventType = "TEXT_BROADCAST_${finalState.name}",
            severity = if (finalState == BroadcastPlaybackState.PLAYED) EventSeverity.INFO else EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "commandId" to command.commandId,
                    "broadcastId" to broadcastId,
                    "state" to finalState.name,
                    "error" to finalError,
                ),
            ).toString(),
        )
    }

    private suspend fun sendReceipt(
        client: DeviceMessageTransport,
        store: BroadcastStore,
        id: String,
        state: BroadcastPlaybackState,
        occurredAt: Long,
        error: String?,
        wallClock: () -> Long,
    ) {
        store.markReceiptAttempt(id, occurredAt)
        client.sendBroadcastReceipt(id, state, occurredAt, error)
        store.markReceiptDelivered(id, wallClock())
    }

    private suspend fun syncPendingBroadcastReceipts(
        client: DeviceMessageTransport,
        store: BroadcastStore,
        wallClock: () -> Long,
    ): Result? {
        for (broadcast in store.pendingReceipts(MAX_BROADCAST_RECEIPTS_PER_RUN)) {
            store.markReceiptAttempt(broadcast.broadcastId, wallClock())
            try {
                broadcastReceiptReplay(broadcast).forEach { receipt ->
                    client.sendBroadcastReceipt(
                        broadcast.broadcastId,
                        receipt.state,
                        receipt.occurredAtEpochMillis,
                        receipt.error,
                    )
                }
                store.markReceiptDelivered(broadcast.broadcastId, wallClock())
            } catch (error: CommunicationException) {
                store.markReceiptFailed(broadcast.broadcastId)
                return if (error.retryable) Result.retry() else Result.failure()
            } catch (error: Throwable) {
                store.markReceiptFailed(broadcast.broadcastId)
                return Result.retry()
            }
        }
        return null
    }

    private suspend fun acknowledgeAppliedCommand(
        client: DeviceMessageTransport,
        store: DeviceCommandStore,
        command: DeviceCommand,
        wallClock: () -> Long,
    ) {
        val stored = requireNotNull(store.find(command.commandId))
        store.markAckAttempt(command.commandId, wallClock())
        client.acknowledgeCommand(stored, "APPLIED")
        store.markAckDelivered(command.commandId, wallClock())
    }

    private suspend fun syncPendingCommandAcks(
        client: DeviceMessageTransport,
        store: DeviceCommandStore,
        wallClock: () -> Long,
    ): Result? {
        for (command in store.pendingAcks(MAX_COMMANDS_PER_RUN)) {
            store.markAckAttempt(command.commandId, wallClock())
            try {
                client.acknowledgeCommand(
                    command,
                    command.state.name,
                    command.lastError?.take(MAX_ERROR_LENGTH),
                )
                store.markAckDelivered(command.commandId, wallClock())
            } catch (error: CommunicationException) {
                store.markAckFailed(command.commandId, error.toString())
                return if (error.retryable) Result.retry() else Result.failure()
            } catch (error: Throwable) {
                store.markAckFailed(command.commandId, error.toString())
                return Result.retry()
            }
        }
        return null
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-communication-sync"
        private const val MAX_CALLS_PER_RUN = 50
        private const val MAX_COMMANDS_PER_RUN = 100
        private const val MAX_BROADCAST_RECEIPTS_PER_RUN = 50
        private const val MAX_ERROR_LENGTH = 1_024
        private val reconciledWorkName = AtomicReference<String?>()

        fun enqueue(context: Context, continuation: Boolean = false) {
            val backendUrl = RuntimeConfigStore(context).load().backendBaseUrl
            val route = backendWorkRoute(LEGACY_UNIQUE_WORK, backendUrl)
            val workManager = WorkManager.getInstance(context)
            if (reconciledWorkName.getAndSet(route.activeName) != route.activeName) {
                workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK)
                workManager.cancelUniqueWork(route.staleName)
            }
            val request = OneTimeWorkRequestBuilder<CommunicationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(route.requiredNetworkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(route.activeName, workPolicyForContinuation(continuation), request)
        }
    }
}
