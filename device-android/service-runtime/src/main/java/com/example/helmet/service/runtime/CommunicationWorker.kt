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
import com.example.helmet.communication.sync.CommandStreamHighWaterRegressionException
import com.example.helmet.communication.sync.DeviceCommandPage
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.TextBroadcast
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.DurableIdentityConflictException
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun isLoopbackBackendUrl(value: String): Boolean = runCatching {
    URI(value).host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")
}.getOrDefault(false)

internal fun requiredNetworkTypeForBackendUrl(value: String): NetworkType =
    if (isLoopbackBackendUrl(value)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED

enum class CommunicationWorkTrigger {
    KICK,
    DURABLE_COMMIT,
    SELF_CONTINUATION,
    STREAM_REPLACED,
    CONFIG_REVISION_CHANGED,
}

internal fun workPolicyForTrigger(trigger: CommunicationWorkTrigger): ExistingWorkPolicy = when (trigger) {
    CommunicationWorkTrigger.KICK -> ExistingWorkPolicy.KEEP
    CommunicationWorkTrigger.SELF_CONTINUATION,
    CommunicationWorkTrigger.STREAM_REPLACED,
    -> ExistingWorkPolicy.APPEND_OR_REPLACE
    CommunicationWorkTrigger.DURABLE_COMMIT,
    CommunicationWorkTrigger.CONFIG_REVISION_CHANGED -> ExistingWorkPolicy.REPLACE
}

internal fun workPolicyForContinuation(continuation: Boolean): ExistingWorkPolicy =
    if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP

internal fun communicationContinuationRequired(
    fetchedMoreCommands: Boolean,
    pendingCallCount: Int,
    pendingApplicationCount: Int,
    pendingAckCount: Int,
    pendingBroadcastReceiptCount: Int,
): Boolean =
    fetchedMoreCommands ||
        pendingCallCount > 0 ||
        pendingApplicationCount > 0 ||
        pendingAckCount > 0 ||
        pendingBroadcastReceiptCount > 0

internal fun attemptRuntimeServiceSignal(signal: () -> Unit): Throwable? =
    runCatching(signal).exceptionOrNull()

internal fun commandBackendConfigurationIsCurrent(expected: RuntimeConfig, current: RuntimeConfig): Boolean =
    expected.revision == current.revision &&
        expected.backendBaseUrl == current.backendBaseUrl &&
        expected.backendBearerToken == current.backendBearerToken &&
        expected.mqttBrokerUri == current.mqttBrokerUri &&
        expected.mqttClientCertificateAlias == current.mqttClientCertificateAlias

private class StaleCommandBackendConfigurationException : Exception()
private class ReplacedCommandStreamException : Exception()
private class CommandStreamRediscoveryException(val retryable: Boolean) : Exception()
private class RetryStreamBoundDeliveryException : Exception()

internal enum class StreamBoundFailureDisposition {
    RETRY,
    REJECT,
    STREAM_REPLACED,
}

internal enum class CommandHighWaterFailureDisposition {
    QUARANTINE,
    STREAM_REPLACED,
}

internal enum class CommandStreamIdentityGateDisposition {
    CURRENT,
    STREAM_REPLACED,
    REJECTED,
}

internal suspend fun commandStreamIdentityGateDisposition(
    expectedCommandStreamId: String,
    discover: suspend () -> DeviceCommandPage,
    verify: suspend (DeviceCommandPage) -> Boolean,
): CommandStreamIdentityGateDisposition {
    val page = discover()
    if (page.commandStreamId != expectedCommandStreamId) {
        return CommandStreamIdentityGateDisposition.STREAM_REPLACED
    }
    return if (verify(page)) {
        CommandStreamIdentityGateDisposition.CURRENT
    } else {
        CommandStreamIdentityGateDisposition.REJECTED
    }
}

internal fun commandHighWaterFailureDisposition(
    error: CommandStreamHighWaterRegressionException,
    expectedCommandStreamId: String,
): CommandHighWaterFailureDisposition =
    if (error.observedCommandStreamId == expectedCommandStreamId) {
        CommandHighWaterFailureDisposition.QUARANTINE
    } else {
        CommandHighWaterFailureDisposition.STREAM_REPLACED
    }

internal suspend fun streamBoundFailureDisposition(
    error: CommunicationException,
    expectedCommandStreamId: String,
    discoverCommandStreamId: suspend () -> String,
): StreamBoundFailureDisposition {
    if (error.statusCode == 401 || error.statusCode == 403) {
        return StreamBoundFailureDisposition.RETRY
    }
    if (error.statusCode != 409) {
        return if (error.retryable) {
            StreamBoundFailureDisposition.RETRY
        } else {
            StreamBoundFailureDisposition.REJECT
        }
    }
    return if (discoverCommandStreamId() == expectedCommandStreamId) {
        StreamBoundFailureDisposition.REJECT
    } else {
        StreamBoundFailureDisposition.STREAM_REPLACED
    }
}

internal suspend fun commandStreamIsCurrentBeforeApplication(
    expectedCommandStreamId: String,
    discoverCommandStreamId: suspend () -> String,
): Boolean = discoverCommandStreamId() == expectedCommandStreamId

internal fun communicationFailureCode(error: Throwable): String = when (error) {
    is CommunicationException -> listOfNotNull(
        error.javaClass.name,
        "retryable=${error.retryable}",
        error.statusCode?.let { "status=$it" },
    ).joinToString(":")
    else -> error.javaClass.name
}.take(256)

internal fun isRecoverableCommunicationFailure(error: CommunicationException): Boolean =
    error.retryable || error.statusCode == 401 || error.statusCode == 403

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

internal data class RemoteCallTransition(
    val callId: String,
    val state: CallState,
    val stateSequence: Long,
    val occurredAtEpochMillis: Long,
    val reason: String?,
)

internal fun parseRemoteCallTransition(payloadJson: String): RemoteCallTransition {
    val payload = JSONObject(payloadJson)
    val callId = payload.opt("callId") as? String
    require(!callId.isNullOrBlank()) { "callId is required" }
    val stateName = payload.opt("state") as? String
    require(!stateName.isNullOrBlank()) { "state is required" }
    val state = runCatching { CallState.valueOf(stateName) }
        .getOrElse { throw IllegalArgumentException("invalid call state", it) }
    val stateSequence = payload.strictPositiveLong("stateSequence")
    require(stateSequence > 1) { "remote call transition sequence must be greater than one" }
    val occurredAt = payload.strictPositiveLong("occurredAtEpochMillis")
    val reasonValue = payload.opt("reason")
    val reason = when (reasonValue) {
        null, JSONObject.NULL -> null
        is String -> reasonValue.also { require(it.length <= 1_024) { "reason is too long" } }
        else -> throw IllegalArgumentException("reason must be a string or null")
    }
    return RemoteCallTransition(callId, state, stateSequence, occurredAt, reason)
}

private fun JSONObject.strictPositiveLong(name: String): Long {
    val raw = opt(name)
    require(raw is Int || raw is Long) { "$name must be an integer" }
    return (raw as Number).toLong().also { require(it > 0) { "$name must be positive" } }
}

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
            broadcast.lastError.takeIf { broadcast.playbackState == BroadcastPlaybackState.FAILED },
        ),
    )
}

internal suspend fun terminalizeFailedBroadcastIfPresent(
    command: DeviceCommand,
    commandStreamId: String,
    broadcasts: BroadcastStore,
    now: Long,
    error: String,
): Long? {
    if (command.type != DeviceCommandType.TEXT_BROADCAST) return null
    val broadcastId = runCatching { JSONObject(command.payloadJson).getString("broadcastId") }
        .getOrNull() ?: return null
    val broadcast = broadcasts.find(commandStreamId, broadcastId) ?: return null
    if (broadcast.playbackState in setOf(
            BroadcastPlaybackState.PLAYED,
            BroadcastPlaybackState.FAILED,
            BroadcastPlaybackState.EXPIRED,
        )
    ) {
        return broadcast.playedAtEpochMillis
    }
    val previousEventAt = broadcast.playingAtEpochMillis ?: broadcast.receivedAtEpochMillis
    if (previousEventAt == Long.MAX_VALUE) return null
    val failedAt = nextBroadcastEventTime(now, previousEventAt)
    check(
        broadcasts.updatePlayback(
            commandStreamId,
            command.deviceId,
            broadcastId,
            BroadcastPlaybackState.FAILED,
            failedAt,
            error,
        ),
    ) { "failed broadcast could not be made terminal" }
    return failedAt
}

class CommunicationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val configStore = RuntimeConfigStore(applicationContext)
        val config = configStore.load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return Result.success()
        val configurationIsCurrent = {
            commandBackendConfigurationIsCurrent(config, configStore.load())
        }
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

        syncPendingCalls(
            httpClient,
            calls,
            events,
            wallClock,
            configurationIsCurrent,
        )
        if (!configurationIsCurrent()) return Result.success()
        val mqttConfigured = config.mqttBrokerUri.isNotBlank() || config.mqttClientCertificateAlias.isNotBlank()
        if (mqttConfigured && (
                config.mqttBrokerUri.isBlank() || config.mqttClientCertificateAlias.isBlank()
            )
        ) {
            return Result.failure()
        }
        val messageTransport: CommunicationTransport = httpClient
        var commandStreamId: String
        var diagnosticCommandStreamId: String? = null
        var fetchedMoreCommands = false
        try {
            val discovery = httpClient.fetchCommandPage(deviceId, 0, COMMAND_STREAM_DISCOVERY_LIMIT)
            if (!configurationIsCurrent()) return Result.success()
            commandStreamId = discovery.commandStreamId
            diagnosticCommandStreamId = commandStreamId
            var adoptionResult = commands.prepareStreamAdoption(
                commandStreamId,
                deviceId,
                discovery.commandHighWaterSequence,
                discovery.commands.firstOrNull(),
            )
            if (adoptionResult == DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY) {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "COMMAND_STREAM_ADOPTION_INCOMPATIBLE",
                )
                return Result.failure()
            }
            var stagedPages = 0
            while (
                adoptionResult == DeviceCommandStore.StreamAdoptionPreparationResult.STAGED &&
                stagedPages < MAX_ADOPTION_PAGES_PER_RUN
            ) {
                val progress = requireNotNull(
                    commands.streamAdoptionProgress(commandStreamId, deviceId),
                )
                val afterSequence = progress.nextSequence - 1L
                val remaining = progress.adoptionThroughSequence - afterSequence
                val adoptionPage = httpClient.fetchCommandPage(
                    deviceId,
                    afterSequence,
                    minOf(MAX_COMMANDS_PER_RUN.toLong(), remaining).toInt(),
                )
                if (adoptionPage.commandStreamId != commandStreamId) {
                    enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                    return Result.success()
                }
                if (!commands.observeStreamHighWater(
                        commandStreamId,
                        deviceId,
                        adoptionPage.commandHighWaterSequence,
                        progress.observedHighWater,
                    )
                ) {
                    recordCommandStreamRejection(
                        events,
                        commandStreamId,
                        "COMMAND_STREAM_CHANGED_DURING_ADOPTION",
                    )
                    return Result.failure()
                }
                if (!configurationIsCurrent()) return Result.success()
                adoptionResult = commands.stageStreamAdoptionPage(
                    commandStreamId,
                    deviceId,
                    progress.adoptionThroughSequence,
                    adoptionPage.commands,
                )
                stagedPages += 1
            }
            if (adoptionResult == DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY) {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "COMMAND_STREAM_CHANGED_DURING_ADOPTION",
                )
                return Result.failure()
            }
            if (adoptionResult == DeviceCommandStore.StreamAdoptionPreparationResult.STAGED) {
                enqueue(applicationContext, CommunicationWorkTrigger.SELF_CONTINUATION)
                return Result.success()
            }
            if (adoptionResult == DeviceCommandStore.StreamAdoptionPreparationResult.READY) {
                if (
                    commands.commitStreamAdoption(commandStreamId, deviceId) !=
                    DeviceCommandStore.StreamAdoptionPreparationResult.READY
                ) {
                    recordCommandStreamRejection(
                        events,
                        commandStreamId,
                        "COMMAND_STREAM_ADOPTION_COMMIT_REJECTED",
                    )
                    return Result.failure()
                }
            }
            val after = commands.maxSequence(commandStreamId, deviceId)
            val page = httpClient.fetchCommandPage(deviceId, after, MAX_COMMANDS_PER_RUN)
            if (page.commandStreamId != commandStreamId) {
                enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                return Result.success()
            }
            if (!commands.observeStreamHighWater(
                    commandStreamId,
                    deviceId,
                    page.commandHighWaterSequence,
                )
            ) {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "COMMAND_STREAM_HIGH_WATER_REGRESSION",
                )
                return Result.failure()
            }
            if (!configurationIsCurrent()) return Result.success()
            try {
                page.commands.forEach { command -> commands.receive(commandStreamId, command) }
            } catch (_: DurableIdentityConflictException) {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "COMMAND_IDENTITY_CONFLICT",
                )
                return Result.failure()
            } catch (_: IllegalArgumentException) {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "SERVER_COMMAND_INVALID",
                )
                return Result.failure()
            }
            fetchedMoreCommands = page.hasMore
        } catch (error: CommandStreamHighWaterRegressionException) {
            val expectedCommandStreamId = diagnosticCommandStreamId
            if (
                expectedCommandStreamId == null ||
                commandHighWaterFailureDisposition(error, expectedCommandStreamId) ==
                CommandHighWaterFailureDisposition.STREAM_REPLACED
            ) {
                enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                return Result.success()
            }
            commands.quarantineHighWaterRegression(
                expectedCommandStreamId,
                deviceId,
                error.observedHighWater,
                error.requestedAfterSequence,
            )
            recordCommandStreamRejection(
                events,
                expectedCommandStreamId,
                "COMMAND_STREAM_HIGH_WATER_REGRESSION",
            )
            return Result.failure()
        } catch (error: CommunicationException) {
            if (isRecoverableCommunicationFailure(error)) return Result.retry()
            recordCommandStreamRejection(
                events,
                diagnosticCommandStreamId,
                "SERVER_PROTOCOL_REJECTED",
            )
            return Result.failure()
        } catch (_: StaleCommandBackendConfigurationException) {
            return Result.success()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return Result.retry()
        }

        if (!configurationIsCurrent()) return Result.success()
        val deliveryIdentityGateResult = verifyInitializedCommandStream(
            httpClient,
            commands,
            events,
            commandStreamId,
            deviceId,
            configurationIsCurrent,
        )
        if (deliveryIdentityGateResult != null) return deliveryIdentityGateResult
        val pendingAckResult = syncPendingCommandAcks(
            httpClient,
            commands,
            commandStreamId,
            deviceId,
            wallClock,
            configurationIsCurrent,
        )
        if (pendingAckResult != null) return pendingAckResult
        val pendingBroadcastReceiptResult = syncPendingBroadcastReceipts(
            messageTransport,
            broadcasts,
            commandStreamId,
            deviceId,
            wallClock,
            configurationIsCurrent,
        )
        if (pendingBroadcastReceiptResult != null) return pendingBroadcastReceiptResult

        AndroidTextPlayback(applicationContext).use { playback ->
            for (command in commands.pendingApplication(commandStreamId, deviceId, MAX_COMMANDS_PER_RUN)) {
                if (!configurationIsCurrent()) return Result.success()
                val applicationIdentityGateResult = verifyInitializedCommandStream(
                    httpClient,
                    commands,
                    events,
                    commandStreamId,
                    deviceId,
                    configurationIsCurrent,
                )
                if (applicationIdentityGateResult != null) return applicationIdentityGateResult
                val result = processCommand(
                    command,
                    commandStreamId,
                    messageTransport,
                    httpClient,
                    calls,
                    broadcasts,
                    commands,
                    events,
                    playback,
                    wallClock,
                    configurationIsCurrent,
                )
                if (result != null) return result
            }
        }
        syncPendingCalls(
            httpClient,
            calls,
            events,
            wallClock,
            configurationIsCurrent,
        )
        if (!configurationIsCurrent()) return Result.success()
        if (communicationContinuationRequired(
                fetchedMoreCommands = fetchedMoreCommands,
                pendingCallCount = calls.pendingCount(),
                pendingApplicationCount = commands.pendingApplicationCount(commandStreamId, deviceId),
                pendingAckCount = commands.pendingAckCount(commandStreamId, deviceId),
                pendingBroadcastReceiptCount = broadcasts.pendingReceiptCount(commandStreamId, deviceId),
            )
        ) {
            enqueue(applicationContext, CommunicationWorkTrigger.SELF_CONTINUATION)
        }
        return Result.success()
    }

    private suspend fun verifyInitializedCommandStream(
        client: CommunicationTransport,
        commands: DeviceCommandStore,
        events: EventStore,
        commandStreamId: String,
        deviceId: String,
        configurationIsCurrent: () -> Boolean,
    ): Result? {
        val disposition = try {
            commandStreamIdentityGateDisposition(
                expectedCommandStreamId = commandStreamId,
                discover = {
                    requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    client.fetchCommandPage(deviceId, 0, COMMAND_STREAM_DISCOVERY_LIMIT).also {
                        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    }
                },
                verify = { page ->
                    commands.verifyInitializedStreamIdentity(
                        commandStreamId,
                        deviceId,
                        page.commandHighWaterSequence,
                        page.commands.firstOrNull(),
                    ).also {
                        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    }
                },
            )
        } catch (_: StaleCommandBackendConfigurationException) {
            return Result.success()
        } catch (error: CommandStreamHighWaterRegressionException) {
            if (
                commandHighWaterFailureDisposition(error, commandStreamId) ==
                CommandHighWaterFailureDisposition.STREAM_REPLACED
            ) {
                enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                return Result.success()
            }
            commands.quarantineHighWaterRegression(
                commandStreamId,
                deviceId,
                error.observedHighWater,
                error.requestedAfterSequence,
            )
            recordCommandStreamRejection(
                events,
                commandStreamId,
                "COMMAND_STREAM_HIGH_WATER_REGRESSION",
            )
            return Result.failure()
        } catch (error: CommunicationException) {
            if (isRecoverableCommunicationFailure(error)) return Result.retry()
            recordCommandStreamRejection(events, commandStreamId, "SERVER_PROTOCOL_REJECTED")
            return Result.failure()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            recordCommandStreamRejection(events, commandStreamId, "COMMAND_STREAM_IDENTITY_CHECK_FAILED")
            return Result.failure()
        }
        return when (disposition) {
            CommandStreamIdentityGateDisposition.CURRENT -> null
            CommandStreamIdentityGateDisposition.STREAM_REPLACED -> {
                enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                Result.success()
            }
            CommandStreamIdentityGateDisposition.REJECTED -> {
                recordCommandStreamRejection(
                    events,
                    commandStreamId,
                    "COMMAND_STREAM_IDENTITY_CONFLICT",
                )
                Result.failure()
            }
        }
    }

    private suspend fun recordCommandStreamRejection(
        events: EventStore,
        commandStreamId: String?,
        reason: String,
    ) {
        events.record(
            eventType = "COMMAND_STREAM_REJECTED",
            severity = EventSeverity.HIGH,
            payloadJson = JSONObject(
                mapOf(
                    "commandStreamId" to (commandStreamId ?: JSONObject.NULL),
                    "reason" to reason,
                ),
            ).toString(),
        )
    }

    private suspend fun classifyStreamBoundFailure(
        client: CommunicationTransport,
        error: CommunicationException,
        commandStreamId: String,
        deviceId: String,
        configurationIsCurrent: () -> Boolean,
    ): StreamBoundFailureDisposition = try {
        streamBoundFailureDisposition(error, commandStreamId) {
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            client.fetchCommandPage(deviceId, 0, COMMAND_STREAM_DISCOVERY_LIMIT)
                .commandStreamId
                .also { requireCurrentCommandBackendConfiguration(configurationIsCurrent) }
        }
    } catch (error: StaleCommandBackendConfigurationException) {
        throw error
    } catch (error: CommunicationException) {
        throw CommandStreamRediscoveryException(isRecoverableCommunicationFailure(error))
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        throw CommandStreamRediscoveryException(retryable = false)
    }

    private suspend fun syncPendingCalls(
        client: CommunicationTransport,
        calls: CallStore,
        events: EventStore,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Result? {
        var retryRequired = false
        for (call in calls.pending(MAX_CALLS_PER_RUN)) {
            if (!configurationIsCurrent()) return Result.success()
            val legacyReconciliation = calls.requiresLegacyReconciliation(call.callId, call.stateSequence)
            try {
                requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                val attemptStarted = if (legacyReconciliation) {
                    calls.markLegacyReconciliationAttempt(call.callId, call.stateSequence, wallClock())
                } else {
                    calls.markAttempt(call.callId, call.stateSequence, wallClock())
                }
                if (!attemptStarted) continue
                requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                val receipt = if (legacyReconciliation) {
                    require(client is HttpCommunicationClient) {
                        "legacy call reconciliation requires HTTP server state"
                    }
                    client.reconcileLegacyCall(call) {
                        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    }
                } else if (client is HttpCommunicationClient) {
                    client.syncCall(call) {
                        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    }
                } else {
                    client.syncCall(call)
                }
                requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                if (legacyReconciliation) {
                    val serverState = runCatching { CallState.valueOf(receipt.state) }
                        .getOrElse { throw IllegalArgumentException("invalid reconciled call state", it) }
                    calls.completeLegacyReconciliation(
                        callId = call.callId,
                        expectedLocalSequence = call.stateSequence,
                        serverState = serverState,
                        serverSequence = receipt.stateSequence,
                        serverUpdatedAtEpochMillis = receipt.updatedAtEpochMillis ?: wallClock(),
                        deliveredAtEpochMillis = wallClock(),
                    )
                } else {
                    calls.markDelivered(call.callId, call.stateSequence, wallClock())
                }
                events.record(
                    eventType = "CALL_STATE_SYNCED",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "callId" to call.callId,
                            "state" to call.state.name,
                            "localStateSequence" to call.stateSequence,
                            "acknowledgedStateSequence" to receipt.acknowledgedStateSequence,
                            "serverCurrentState" to receipt.state,
                            "serverCurrentStateSequence" to receipt.stateSequence,
                            "deduplicated" to receipt.deduplicated,
                            "legacyReconciliation" to legacyReconciliation,
                        ),
                    ).toString(),
                )
            } catch (_: StaleCommandBackendConfigurationException) {
                return Result.success()
            } catch (error: CommunicationException) {
                val recoverable = isRecoverableCommunicationFailure(error)
                if (legacyReconciliation) {
                    if (recoverable) {
                        calls.markLegacyReconciliationFailed(
                            call.callId,
                            call.stateSequence,
                            communicationFailureCode(error),
                        )
                    } else {
                        calls.markRejected(
                            call.callId,
                            call.stateSequence,
                            communicationFailureCode(error),
                        )
                    }
                } else if (recoverable) {
                    calls.markFailed(call.callId, call.stateSequence, communicationFailureCode(error))
                } else {
                    calls.markRejected(call.callId, call.stateSequence, communicationFailureCode(error))
                }
                events.record(
                    eventType = "CALL_STATE_SYNC_FAILED",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "callId" to call.callId,
                            "state" to call.state.name,
                            "retryable" to recoverable,
                            "errorType" to error.javaClass.simpleName,
                        ),
                    ).toString(),
                )
                if (recoverable) retryRequired = true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (legacyReconciliation) {
                    calls.markLegacyReconciliationFailed(
                        call.callId,
                        call.stateSequence,
                        communicationFailureCode(error),
                    )
                } else {
                    calls.markFailed(call.callId, call.stateSequence, communicationFailureCode(error))
                }
                events.record(
                    eventType = "CALL_STATE_SYNC_FAILED",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "callId" to call.callId,
                            "state" to call.state.name,
                            "retryable" to true,
                            "errorType" to error.javaClass.simpleName,
                        ),
                    ).toString(),
                )
                retryRequired = true
            }
        }
        return if (retryRequired) Result.retry() else null
    }

    private suspend fun processCommand(
        command: DeviceCommand,
        commandStreamId: String,
        messageTransport: CommunicationTransport,
        commandAcknowledgementClient: CommunicationTransport,
        calls: CallStore,
        broadcasts: BroadcastStore,
        commands: DeviceCommandStore,
        events: EventStore,
        playback: TextPlayback,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Result? {
        return try {
        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
        when (command.type) {
            DeviceCommandType.CALL_STATE ->
                processCallCommand(command, commandStreamId, calls, commands, events, wallClock)
            DeviceCommandType.TEXT_BROADCAST ->
                processBroadcastCommand(
                    command,
                    commandStreamId,
                    messageTransport,
                    broadcasts,
                    commands,
                    events,
                    playback,
                    wallClock,
                    configurationIsCurrent,
                )
        }
        acknowledgeAppliedCommand(
            commandAcknowledgementClient,
            commands,
            commandStreamId,
            command,
            wallClock,
            configurationIsCurrent,
        )
        null
    } catch (_: StaleCommandBackendConfigurationException) {
        Result.success()
    } catch (_: ReplacedCommandStreamException) {
        enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
        Result.success()
    } catch (_: RetryStreamBoundDeliveryException) {
        Result.retry()
    } catch (error: CommandStreamRediscoveryException) {
        commands.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REDISCOVERY_FAILED")
        if (error.retryable) Result.retry() else Result.failure()
    } catch (error: CommunicationException) {
        if (isRecoverableCommunicationFailure(error)) {
            commands.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(error))
            Result.retry()
        } else {
            commands.markAckRejected(commandStreamId, command.commandId, communicationFailureCode(error))
            null
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val now = maxOf(wallClock(), command.receivedAtEpochMillis)
        val failureCode = communicationFailureCode(error)
        val storedBeforeFailure = commands.find(commandStreamId, command.commandId)
        if (storedBeforeFailure != null && storedBeforeFailure.state != DeviceCommandState.RECEIVED) {
            if (storedBeforeFailure.ackDeliveryState in setOf(DeliveryState.DELIVERED, DeliveryState.REJECTED)) {
                return null
            }
            commands.markAckFailed(commandStreamId, command.commandId, failureCode)
            runCatching {
                events.record(
                    eventType = "DEVICE_COMMAND_ACK_DEFERRED",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "commandId" to command.commandId,
                            "type" to command.type.name,
                            "errorType" to failureCode,
                        ),
                    ).toString(),
                )
            }.onFailure { eventError ->
                if (eventError is CancellationException) throw eventError
            }
            return Result.retry()
        }
        val failedAt = terminalizeFailedBroadcastIfPresent(
            command,
            commandStreamId,
            broadcasts,
            now,
            failureCode,
        ) ?: now
        commands.markFailed(commandStreamId, command.commandId, failedAt, failureCode)
        val stored = requireNotNull(commands.find(commandStreamId, command.commandId))
        commands.markAckAttempt(commandStreamId, command.commandId, now)
        val acknowledgementResult = try {
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            commandAcknowledgementClient.acknowledgeCommand(
                commandStreamId,
                stored,
                "FAILED",
                stored.lastError?.take(MAX_ERROR_LENGTH),
            )
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            commands.markAckDelivered(commandStreamId, command.commandId, wallClock())
            null
        } catch (_: StaleCommandBackendConfigurationException) {
            Result.success()
        } catch (_: ReplacedCommandStreamException) {
            enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
            Result.success()
        } catch (rediscoveryError: CommandStreamRediscoveryException) {
            commands.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REDISCOVERY_FAILED")
            if (rediscoveryError.retryable) Result.retry() else Result.failure()
        } catch (ackError: CommunicationException) {
            try {
                when (classifyStreamBoundFailure(
                    commandAcknowledgementClient,
                    ackError,
                    commandStreamId,
                    command.deviceId,
                    configurationIsCurrent,
                )) {
                    StreamBoundFailureDisposition.RETRY -> {
                        commands.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(ackError))
                        Result.retry()
                    }
                    StreamBoundFailureDisposition.REJECT -> {
                        commands.markAckRejected(commandStreamId, command.commandId, communicationFailureCode(ackError))
                        null
                    }
                    StreamBoundFailureDisposition.STREAM_REPLACED -> {
                        commands.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REPLACED")
                        enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                        Result.success()
                    }
                }
            } catch (_: StaleCommandBackendConfigurationException) {
                commands.markAckFailed(commandStreamId, command.commandId, "COMMAND_BACKEND_CONFIGURATION_CHANGED")
                Result.success()
            } catch (rediscoveryError: CommandStreamRediscoveryException) {
                commands.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REDISCOVERY_FAILED")
                if (rediscoveryError.retryable) Result.retry() else Result.failure()
            }
        } catch (ackError: Throwable) {
            if (ackError is CancellationException) throw ackError
            commands.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(ackError))
            Result.retry()
        }
        events.record(
            eventType = "DEVICE_COMMAND_FAILED",
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "commandId" to command.commandId,
                    "type" to command.type.name,
                    "errorType" to communicationFailureCode(error),
                ),
            ).toString(),
        )
        acknowledgementResult
        }
    }

    private suspend fun processCallCommand(
        command: DeviceCommand,
        commandStreamId: String,
        calls: CallStore,
        commands: DeviceCommandStore,
        events: EventStore,
        wallClock: () -> Long,
    ) {
        val transition = parseRemoteCallTransition(command.payloadJson)
        calls.applyRemoteTransition(
            callId = transition.callId,
            target = transition.state,
            stateSequence = transition.stateSequence,
            occurredAtEpochMillis = transition.occurredAtEpochMillis,
            reason = transition.reason,
        )
        val appliedAt = maxOf(
            transition.occurredAtEpochMillis,
            command.receivedAtEpochMillis,
            wallClock(),
        )
        commands.markApplied(commandStreamId, command.commandId, appliedAt)
        events.record(
            eventType = "CALL_STATE_APPLIED",
            severity = EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf(
                    "commandId" to command.commandId,
                    "callId" to transition.callId,
                    "state" to transition.state.name,
                    "stateSequence" to transition.stateSequence,
                    "occurredAtEpochMillis" to transition.occurredAtEpochMillis,
                ),
            ).toString(),
        )
        attemptRuntimeServiceSignal {
            ContextCompat.startForegroundService(
                applicationContext,
                HelmetService.callStateIntent(applicationContext, transition.callId, transition.state),
            )
        }?.let { error ->
            // Android 12 can reject a foreground-service start while this worker is running in the
            // background. The call transition is already durable and HelmetService observes it on
            // the next sticky, boot, launcher, or watchdog recovery.
            events.record(
                eventType = "CALL_STATE_RUNTIME_SIGNAL_DEFERRED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "commandId" to command.commandId,
                        "callId" to transition.callId,
                        "state" to transition.state.name,
                        "errorType" to error.javaClass.name,
                    ),
                ).toString(),
            )
        }
    }

    private suspend fun processBroadcastCommand(
        command: DeviceCommand,
        commandStreamId: String,
        client: CommunicationTransport,
        broadcasts: BroadcastStore,
        commands: DeviceCommandStore,
        events: EventStore,
        playback: TextPlayback,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
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
        broadcasts.receive(commandStreamId, message)
        var stored = requireNotNull(broadcasts.find(commandStreamId, broadcastId))
        var receiptDeliveryAvailable = sendReceiptWithoutBlockingPlayback(
            client,
            broadcasts,
            commandStreamId,
            command.deviceId,
            broadcastId,
            BroadcastPlaybackState.RECEIVED,
            stored.receivedAtEpochMillis,
            null,
            wallClock,
            configurationIsCurrent,
        )
        if (stored.playbackState == BroadcastPlaybackState.PLAYED ||
            stored.playbackState == BroadcastPlaybackState.FAILED ||
            stored.playbackState == BroadcastPlaybackState.EXPIRED
        ) {
            val completedAt = requireNotNull(stored.playedAtEpochMillis)
            if (receiptDeliveryAvailable) {
                sendReceiptWithoutBlockingPlayback(
                    client,
                    broadcasts,
                    commandStreamId,
                    command.deviceId,
                    broadcastId,
                    stored.playbackState,
                    completedAt,
                    stored.lastError.takeIf {
                        stored.playbackState == BroadcastPlaybackState.FAILED
                    },
                    wallClock,
                    configurationIsCurrent,
                )
            }
            commands.markApplied(commandStreamId, command.commandId, completedAt)
            return
        }
        val expiresAt = stored.expiresAtEpochMillis
        val finalState: BroadcastPlaybackState
        val finalError: String?
        var latestEventAt = receivedAt
        if (expiresAt != null && receivedAt >= expiresAt) {
            finalState = BroadcastPlaybackState.EXPIRED
            finalError = null
        } else {
            val playingAt = stored.playingAtEpochMillis
                ?: nextBroadcastEventTime(wallClock(), stored.receivedAtEpochMillis)
            broadcasts.updatePlayback(
                commandStreamId,
                command.deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYING,
                playingAt,
            )
            if (receiptDeliveryAvailable) {
                receiptDeliveryAvailable = sendReceiptWithoutBlockingPlayback(
                    client,
                    broadcasts,
                    commandStreamId,
                    command.deviceId,
                    broadcastId,
                    BroadcastPlaybackState.PLAYING,
                    playingAt,
                    null,
                    wallClock,
                    configurationIsCurrent,
                )
            }
            latestEventAt = playingAt
            stored = requireNotNull(broadcasts.find(commandStreamId, broadcastId))
            val outcome = playback.play(stored.text, stored.language, "broadcast-$broadcastId")
            finalState = if (outcome.success) BroadcastPlaybackState.PLAYED else BroadcastPlaybackState.FAILED
            finalError = outcome.error ?: "TTS_PLAYBACK_FAILED"
        }
        val completedAt = nextBroadcastEventTime(wallClock(), latestEventAt)
        broadcasts.updatePlayback(
            commandStreamId,
            command.deviceId,
            broadcastId,
            finalState,
            completedAt,
            finalError,
        )
        if (receiptDeliveryAvailable) {
            sendReceiptWithoutBlockingPlayback(
                client,
                broadcasts,
                commandStreamId,
                command.deviceId,
                broadcastId,
                finalState,
                completedAt,
                finalError,
                wallClock,
                configurationIsCurrent,
            )
        }
        commands.markApplied(commandStreamId, command.commandId, completedAt)
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

    private suspend fun sendReceiptWithoutBlockingPlayback(
        client: CommunicationTransport,
        store: BroadcastStore,
        commandStreamId: String,
        deviceId: String,
        id: String,
        state: BroadcastPlaybackState,
        occurredAt: Long,
        error: String?,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Boolean = try {
        sendReceipt(
            client,
            store,
            commandStreamId,
            deviceId,
            id,
            state,
            occurredAt,
            error,
            wallClock,
            configurationIsCurrent,
        )
    } catch (_: RetryStreamBoundDeliveryException) {
        false
    } catch (_: CommandStreamRediscoveryException) {
        false
    }

    private suspend fun sendReceipt(
        client: CommunicationTransport,
        store: BroadcastStore,
        commandStreamId: String,
        deviceId: String,
        id: String,
        state: BroadcastPlaybackState,
        occurredAt: Long,
        error: String?,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Boolean {
        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
        if (!store.markReceiptAttempt(commandStreamId, deviceId, id, occurredAt)) {
            val current = store.find(commandStreamId, id) ?: return false
            if (current.receiptDeliveryState == DeliveryState.REJECTED) return false
        }
        try {
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            client.sendBroadcastReceipt(commandStreamId, id, state, occurredAt, error)
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            store.markReceiptDelivered(commandStreamId, deviceId, id, wallClock())
            return true
        } catch (failure: CommunicationException) {
            val disposition = try {
                classifyStreamBoundFailure(
                    client,
                    failure,
                    commandStreamId,
                    deviceId,
                    configurationIsCurrent,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                store.markReceiptFailed(commandStreamId, deviceId, id)
                throw error
            }
            when (disposition) {
                StreamBoundFailureDisposition.RETRY -> {
                    store.markReceiptFailed(commandStreamId, deviceId, id)
                    throw RetryStreamBoundDeliveryException()
                }
                StreamBoundFailureDisposition.REJECT -> {
                    store.markReceiptRejected(
                        commandStreamId,
                        deviceId,
                        id,
                        communicationFailureCode(failure),
                    )
                    return false
                }
                StreamBoundFailureDisposition.STREAM_REPLACED -> {
                    store.markReceiptFailed(commandStreamId, deviceId, id)
                    throw ReplacedCommandStreamException()
                }
            }
        }
    }

    private suspend fun syncPendingBroadcastReceipts(
        client: CommunicationTransport,
        store: BroadcastStore,
        commandStreamId: String,
        deviceId: String,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Result? {
        for (broadcast in store.pendingReceipts(commandStreamId, deviceId, MAX_BROADCAST_RECEIPTS_PER_RUN)) {
            if (!configurationIsCurrent()) return Result.success()
            store.markReceiptAttempt(commandStreamId, deviceId, broadcast.broadcastId, wallClock())
            try {
                broadcastReceiptReplay(broadcast).forEach { receipt ->
                    requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                    client.sendBroadcastReceipt(
                        commandStreamId,
                        broadcast.broadcastId,
                        receipt.state,
                        receipt.occurredAtEpochMillis,
                        receipt.error,
                    )
                    requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                }
                store.markReceiptDelivered(commandStreamId, deviceId, broadcast.broadcastId, wallClock())
            } catch (_: StaleCommandBackendConfigurationException) {
                return Result.success()
            } catch (_: ReplacedCommandStreamException) {
                enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                return Result.success()
            } catch (rediscoveryError: CommandStreamRediscoveryException) {
                store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                return if (rediscoveryError.retryable) Result.retry() else Result.failure()
            } catch (error: CommunicationException) {
                val disposition = try {
                    classifyStreamBoundFailure(
                        client,
                        error,
                        commandStreamId,
                        deviceId,
                        configurationIsCurrent,
                    )
                } catch (_: StaleCommandBackendConfigurationException) {
                    store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                    return Result.success()
                } catch (rediscoveryError: CommandStreamRediscoveryException) {
                    store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                    return if (rediscoveryError.retryable) Result.retry() else Result.failure()
                }
                when (disposition) {
                    StreamBoundFailureDisposition.RETRY -> {
                        store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                        return Result.retry()
                    }
                    StreamBoundFailureDisposition.REJECT -> store.markReceiptRejected(
                        commandStreamId,
                        deviceId,
                        broadcast.broadcastId,
                        communicationFailureCode(error),
                    )
                    StreamBoundFailureDisposition.STREAM_REPLACED -> {
                        store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                        enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                        return Result.success()
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                store.markReceiptFailed(commandStreamId, deviceId, broadcast.broadcastId)
                return Result.retry()
            }
        }
        return null
    }

    private suspend fun acknowledgeAppliedCommand(
        client: CommunicationTransport,
        store: DeviceCommandStore,
        commandStreamId: String,
        command: DeviceCommand,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ) {
        val stored = requireNotNull(store.find(commandStreamId, command.commandId))
        requireCurrentCommandBackendConfiguration(configurationIsCurrent)
        store.markAckAttempt(commandStreamId, command.commandId, wallClock())
        try {
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            client.acknowledgeCommand(commandStreamId, stored, "APPLIED")
            requireCurrentCommandBackendConfiguration(configurationIsCurrent)
            store.markAckDelivered(commandStreamId, command.commandId, wallClock())
        } catch (error: CommunicationException) {
            val disposition = try {
                classifyStreamBoundFailure(
                    client,
                    error,
                    commandStreamId,
                    command.deviceId,
                    configurationIsCurrent,
                )
            } catch (classificationError: Throwable) {
                if (classificationError is CancellationException) throw classificationError
                store.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REDISCOVERY_FAILED")
                throw classificationError
            }
            when (disposition) {
                StreamBoundFailureDisposition.RETRY -> {
                    store.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(error))
                    throw RetryStreamBoundDeliveryException()
                }
                StreamBoundFailureDisposition.REJECT ->
                    store.markAckRejected(commandStreamId, command.commandId, communicationFailureCode(error))
                StreamBoundFailureDisposition.STREAM_REPLACED -> {
                    store.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REPLACED")
                    throw ReplacedCommandStreamException()
                }
            }
        }
    }

    private suspend fun syncPendingCommandAcks(
        client: CommunicationTransport,
        store: DeviceCommandStore,
        commandStreamId: String,
        deviceId: String,
        wallClock: () -> Long,
        configurationIsCurrent: () -> Boolean,
    ): Result? {
        for (command in store.pendingAcks(commandStreamId, deviceId, MAX_COMMANDS_PER_RUN)) {
            if (!configurationIsCurrent()) return Result.success()
            store.markAckAttempt(commandStreamId, command.commandId, wallClock())
            try {
                requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                client.acknowledgeCommand(
                    commandStreamId,
                    command,
                    command.state.name,
                    command.lastError?.take(MAX_ERROR_LENGTH),
                )
                requireCurrentCommandBackendConfiguration(configurationIsCurrent)
                store.markAckDelivered(commandStreamId, command.commandId, wallClock())
            } catch (_: StaleCommandBackendConfigurationException) {
                return Result.success()
            } catch (error: CommunicationException) {
                val disposition = try {
                    classifyStreamBoundFailure(
                        client,
                        error,
                        commandStreamId,
                        deviceId,
                        configurationIsCurrent,
                    )
                } catch (_: StaleCommandBackendConfigurationException) {
                    store.markAckFailed(commandStreamId, command.commandId, "COMMAND_BACKEND_CONFIGURATION_CHANGED")
                    return Result.success()
                } catch (rediscoveryError: CommandStreamRediscoveryException) {
                    store.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REDISCOVERY_FAILED")
                    return if (rediscoveryError.retryable) Result.retry() else Result.failure()
                }
                when (disposition) {
                    StreamBoundFailureDisposition.RETRY -> {
                        store.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(error))
                        return Result.retry()
                    }
                    StreamBoundFailureDisposition.REJECT ->
                        store.markAckRejected(commandStreamId, command.commandId, communicationFailureCode(error))
                    StreamBoundFailureDisposition.STREAM_REPLACED -> {
                        store.markAckFailed(commandStreamId, command.commandId, "COMMAND_STREAM_REPLACED")
                        enqueue(applicationContext, CommunicationWorkTrigger.STREAM_REPLACED)
                        return Result.success()
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                store.markAckFailed(commandStreamId, command.commandId, communicationFailureCode(error))
                return Result.retry()
            }
        }
        return null
    }

    companion object {
        private const val LEGACY_UNIQUE_WORK = "helmet-communication-sync"
        private const val MAX_CALLS_PER_RUN = 50
        private const val COMMAND_STREAM_DISCOVERY_LIMIT = 1
        private const val MAX_COMMANDS_PER_RUN = 100
        private const val MAX_ADOPTION_PAGES_PER_RUN = 10
        private const val MAX_BROADCAST_RECEIPTS_PER_RUN = 50
        private const val MAX_ERROR_LENGTH = 1_024
        private val reconciledWorkName = AtomicReference<String?>()

        fun enqueue(
            context: Context,
            trigger: CommunicationWorkTrigger = CommunicationWorkTrigger.KICK,
        ) {
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
            workManager.enqueueUniqueWork(route.activeName, workPolicyForTrigger(trigger), request)
        }
    }
}

private fun requireCurrentCommandBackendConfiguration(configurationIsCurrent: () -> Boolean) {
    if (!configurationIsCurrent()) throw StaleCommandBackendConfigurationException()
}
