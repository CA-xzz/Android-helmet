package com.example.helmet.communication.sync

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.feature.connectivity.HttpResponseTooLargeException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class HttpCommunicationClientTest {
    @Test
    fun oversizedResponseIsPermanentWhileOtherIoFailuresRemainRetryable() {
        val oversized = communicationIoFailure(HttpResponseTooLargeException(8 * 1024 * 1024))
        val disconnected = communicationIoFailure(IOException("disconnected"))

        assertEquals(false, oversized.retryable)
        assertEquals(null, oversized.statusCode)
        assertEquals(true, disconnected.retryable)
        assertEquals(null, disconnected.statusCode)
    }

    @Test
    fun endpointRequiresHttpsExceptForLoopbackTests() {
        assertEquals(
            "https://communication.example.test",
            HttpCommunicationClient.validateAndNormalizeBaseUrl("https://communication.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpCommunicationClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpCommunicationClient.validateAndNormalizeBaseUrl("http://communication.example.test")
        }
    }

    @Test
    fun responseSequenceMustBeContinuousAndWithinRequestedLimit() {
        HttpCommunicationClient.validateResponseSequence("signal", listOf(41, 42, 43), 40, 3)
        HttpCommunicationClient.validateResponseSequence(
            "signal",
            listOf(Long.MAX_VALUE),
            Long.MAX_VALUE - 1L,
            1,
        )
        HttpCommunicationClient.validateResponseSequence("signal", emptyList(), Long.MAX_VALUE, 100)

        val gap = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.validateResponseSequence("signal", listOf(41, 43), 40, 3)
        }
        assertEquals(false, gap.retryable)

        val overflow = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.validateResponseSequence("command", listOf(1, 2), 0, 1)
        }
        assertEquals(false, overflow.retryable)

        val exhausted = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.validateResponseSequence(
                "command",
                listOf(Long.MAX_VALUE),
                Long.MAX_VALUE,
                1,
            )
        }
        assertEquals(false, exhausted.retryable)
    }

    @Test
    fun acknowledgedHistoryAdvancesSequenceWithoutBecomingPendingApplication() {
        val command = HttpCommunicationClient.parseDeviceCommand(
            commandJson(acknowledged = true),
            receivedAtEpochMillis = 1_786_000_000_100,
        )

        assertEquals(41, command.serverSequence)
        assertEquals(DeviceCommandState.ACKNOWLEDGED, command.state)
        assertEquals(DeliveryState.DELIVERED, command.ackDeliveryState)
    }

    @Test
    fun unacknowledgedHistoryRemainsPendingForRoomIdempotentApplication() {
        val command = HttpCommunicationClient.parseDeviceCommand(
            commandJson(acknowledged = false),
            receivedAtEpochMillis = 1_786_000_000_100,
        )

        assertEquals(DeviceCommandState.RECEIVED, command.state)
        assertEquals(DeliveryState.PENDING, command.ackDeliveryState)
    }

    @Test
    fun missingAcknowledgedFlagIsRejectedInsteadOfAssumedPending() {
        val invalid = commandJson(acknowledged = false).also { it.remove("acknowledged") }

        val error = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommand(invalid, 1_786_000_000_100)
        }

        assertEquals(false, error.retryable)
    }

    @Test
    fun commandIntegerIdentityFieldsRejectFractionsAndNumericStrings() {
        listOf(1.5, "41").forEach { invalidSequence ->
            val error = assertThrows(CommunicationException::class.java) {
                HttpCommunicationClient.parseDeviceCommand(
                    commandJson(acknowledged = false).put("sequence", invalidSequence),
                    1_786_000_000_100,
                )
            }
            assertEquals(false, error.retryable)
        }
        listOf(1.5, "1786000000000").forEach { invalidCreatedAt ->
            val error = assertThrows(CommunicationException::class.java) {
                HttpCommunicationClient.parseDeviceCommand(
                    commandJson(acknowledged = false).put("createdAtEpochMillis", invalidCreatedAt),
                    1_786_000_000_100,
                )
            }
            assertEquals(false, error.retryable)
        }
        val maximum = HttpCommunicationClient.parseDeviceCommand(
            commandJson(acknowledged = false)
                .put("sequence", Long.MAX_VALUE)
                .put("createdAtEpochMillis", Long.MAX_VALUE),
            1_786_000_000_100,
        )
        assertEquals(Long.MAX_VALUE, maximum.serverSequence)
        assertEquals(Long.MAX_VALUE, maximum.createdAtEpochMillis)
    }

    @Test
    fun unknownCommandTypeAndNonObjectPayloadArePermanentProtocolErrors() {
        val unknownType = commandJson(acknowledged = false).put("type", "UNKNOWN_COMMAND")
        val typeError = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommand(unknownType, 1_786_000_000_100)
        }
        assertEquals(false, typeError.retryable)

        val invalidPayload = commandJson(acknowledged = false).put("payload", "not-an-object")
        val payloadError = assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommandPage(
                JSONObject()
                    .put("commandStreamId", "aaaaaaaa-0000-0000-0000-000000000040")
                    .put("commandHighWaterSequence", 41)
                    .put("commands", JSONArray().put(invalidPayload))
                    .put("hasMore", false)
                    .put("nextAfterSequence", JSONObject.NULL),
                "device-a",
                40,
                1,
                1_786_000_000_100,
            )
        }
        assertEquals(false, payloadError.retryable)
    }

    @Test
    fun commandPageRequiresCanonicalPersistentStreamIdentity() {
        val streamId = "aaaaaaaa-0000-0000-0000-000000000041"
        val page = HttpCommunicationClient.parseDeviceCommandPage(
            JSONObject()
                .put("commandStreamId", streamId)
                .put("commandHighWaterSequence", 41)
                .put("commands", JSONArray().put(commandJson(acknowledged = false)))
                .put("hasMore", false)
                .put("nextAfterSequence", JSONObject.NULL),
            deviceId = "device-a",
            afterSequence = 40,
            limit = 1,
            receivedAtEpochMillis = 1_786_000_000_100,
        )

        assertEquals(streamId, page.commandStreamId)
        assertEquals(41L, page.commandHighWaterSequence)
        assertEquals(listOf(41L), page.commands.map(DeviceCommand::serverSequence))
        assertEquals(false, page.hasMore)
        assertEquals(null, page.nextAfterSequence)

        val missing = JSONObject()
            .put("commands", JSONArray())
            .put("hasMore", false)
            .put("nextAfterSequence", JSONObject.NULL)
        assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommandPage(missing, "device-a", 0, 1, 1)
        }
        assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.requireCanonicalCommandStreamId(streamId.uppercase())
        }
    }

    @Test
    fun commandPageRejectsMissingOrRegressedHighWaterSequence() {
        val streamId = "aaaaaaaa-0000-0000-0000-000000000044"
        val missing = JSONObject()
            .put("commandStreamId", streamId)
            .put("commands", JSONArray())
            .put("hasMore", false)
            .put("nextAfterSequence", JSONObject.NULL)
        assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommandPage(missing, "device-a", 0, 1, 1)
        }

        val behindPage = JSONObject()
            .put("commandStreamId", streamId)
            .put("commandHighWaterSequence", 40L)
            .put("commands", JSONArray().put(commandJson(acknowledged = false)))
            .put("hasMore", false)
            .put("nextAfterSequence", JSONObject.NULL)
        assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommandPage(behindPage, "device-a", 40, 1, 1)
        }
    }

    @Test
    fun exhaustedCommandCursorAcceptsOnlyACompleteEmptyPage() {
        val streamId = "aaaaaaaa-0000-0000-0000-000000000042"
        val emptyPage = HttpCommunicationClient.parseDeviceCommandPage(
            JSONObject()
                .put("commandStreamId", streamId)
                .put("commandHighWaterSequence", Long.MAX_VALUE)
                .put("commands", JSONArray())
                .put("hasMore", false)
                .put("nextAfterSequence", JSONObject.NULL),
            deviceId = "device-a",
            afterSequence = Long.MAX_VALUE,
            limit = 100,
            receivedAtEpochMillis = 1_786_000_000_100,
        )
        assertEquals(emptyList<DeviceCommand>(), emptyPage.commands)

        val finalCommand = commandJson(acknowledged = false).put("sequence", Long.MAX_VALUE)
        val finalPage = HttpCommunicationClient.parseDeviceCommandPage(
            JSONObject()
                .put("commandStreamId", streamId)
                .put("commandHighWaterSequence", Long.MAX_VALUE)
                .put("commands", JSONArray().put(finalCommand))
                .put("hasMore", false)
                .put("nextAfterSequence", JSONObject.NULL),
            deviceId = "device-a",
            afterSequence = Long.MAX_VALUE - 1L,
            limit = 1,
            receivedAtEpochMillis = 1_786_000_000_100,
        )
        assertEquals(Long.MAX_VALUE, finalPage.commands.single().serverSequence)

        val impossibleContinuation = JSONObject()
            .put("commandStreamId", streamId)
            .put("commandHighWaterSequence", Long.MAX_VALUE)
            .put("commands", JSONArray().put(finalCommand))
            .put("hasMore", true)
            .put("nextAfterSequence", Long.MAX_VALUE)
        assertThrows(CommunicationException::class.java) {
            HttpCommunicationClient.parseDeviceCommandPage(
                impossibleContinuation,
                "device-a",
                Long.MAX_VALUE - 1L,
                1,
                1_786_000_000_100,
            )
        }
    }

    @Test
    fun commandAcknowledgementBindsThePersistentStreamIdentity() {
        val streamId = "00000000-0000-0000-0000-000000000042"
        val command = DeviceCommand(
            commandId = "command-ack",
            deviceId = "device-a",
            serverSequence = 1,
            type = DeviceCommandType.CALL_STATE,
            payloadJson = "{}",
            createdAtEpochMillis = 100,
            state = DeviceCommandState.APPLIED,
            receivedAtEpochMillis = 101,
            appliedAtEpochMillis = 102,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )

        val body = deviceCommandAcknowledgementBody(streamId, command, "APPLIED", null)

        assertEquals(streamId, body.getString("commandStreamId"))
        assertEquals("APPLIED", body.getString("status"))
        assertEquals(102L, body.getLong("occurredAtEpochMillis"))
        assertEquals(true, body.isNull("error"))
    }

    @Test
    fun broadcastReceiptBindsThePersistentStreamIdentity() {
        val streamId = "00000000-0000-0000-0000-000000000043"

        val body = broadcastReceiptBody(
            streamId,
            BroadcastPlaybackState.PLAYED,
            123L,
            null,
        )

        assertEquals(streamId, body.getString("commandStreamId"))
        assertEquals(BroadcastPlaybackState.PLAYED.name, body.getString("state"))
        assertEquals(123L, body.getLong("occurredAtEpochMillis"))
        assertEquals(true, body.isNull("error"))
    }

    @Test
    fun offlineOutgoingHangupReplaysCreateBeforeEndedTransition() {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val call = call(state = CallState.ENDED, sequence = 2)

        val receipt = syncCallWithRequest(call) { _, path, body ->
            requests += path to body
            when (path) {
                "/v1/calls" -> receiptJson(CallState.REQUESTED, 1)
                "/v1/calls/call-offline/transitions" -> receiptJson(CallState.ENDED, 2)
                else -> error("unexpected path $path")
            }
        }

        assertEquals(listOf("/v1/calls", "/v1/calls/call-offline/transitions"), requests.map { it.first })
        assertEquals(CallState.REQUESTED.name, requests.first().second.getString("state"))
        assertEquals(1L, requests.first().second.getLong("stateSequence"))
        assertEquals(CallState.ENDED.name, requests.last().second.getString("state"))
        assertEquals(2L, receipt.stateSequence)
    }

    @Test
    fun delayedCreateRetryStillReplaysAndPreciselyAcknowledgesHistoricalTransition() {
        val paths = mutableListOf<String>()
        val call = call(state = CallState.ENDED, sequence = 2)

        val receipt = syncCallWithRequest(call) { _, path, _ ->
            paths += path
            when (path) {
                "/v1/calls" -> receiptJson(
                    CallState.ENDED,
                    3,
                    acknowledgedState = CallState.REQUESTED,
                    acknowledgedSequence = 1,
                    deduplicated = true,
                )
                else -> receiptJson(
                    CallState.ENDED,
                    3,
                    acknowledgedState = CallState.ENDED,
                    acknowledgedSequence = 2,
                    deduplicated = true,
                )
            }
        }

        assertEquals(listOf("/v1/calls", "/v1/calls/call-offline/transitions"), paths)
        assertEquals(3L, receipt.stateSequence)
        assertEquals(2L, receipt.acknowledgedStateSequence)
    }

    @Test
    fun lostInitialCreateResponseAcceptsAnAlreadyAdvancedServerCall() {
        val call = call(state = CallState.REQUESTED, sequence = 1)

        val receipt = syncCallWithRequest(call) { _, path, _ ->
            assertEquals("/v1/calls", path)
            receiptJson(
                CallState.ACCEPTED,
                3,
                acknowledgedState = CallState.REQUESTED,
                acknowledgedSequence = 1,
                deduplicated = true,
            )
        }

        assertEquals(CallState.ACCEPTED.name, receipt.state)
        assertEquals(3L, receipt.stateSequence)
    }

    @Test
    fun advancedServerStateDoesNotSubsumeAnUnacknowledgedHistoricalBranch() {
        val historical = call(state = CallState.RINGING, sequence = 2)
        var requestCount = 0

        val receipt = syncCallWithRequest(historical) { _, path, _ ->
            requestCount += 1
            if (path == "/v1/calls") {
                receiptJson(
                    CallState.CONNECTED,
                    5,
                    acknowledgedState = CallState.REQUESTED,
                    acknowledgedSequence = 1,
                    deduplicated = true,
                )
            } else {
                receiptJson(
                    CallState.CONNECTED,
                    5,
                    acknowledgedState = CallState.RINGING,
                    acknowledgedSequence = 2,
                    deduplicated = true,
                )
            }
        }

        assertEquals(2, requestCount)
        assertEquals(CallState.CONNECTED.name, receipt.state)
        assertEquals(5L, receipt.stateSequence)
        assertEquals(CallState.RINGING.name, receipt.acknowledgedState)
    }

    @Test
    fun staleCallReceiptCannotMarkNewerLocalStateDelivered() {
        val call = call(state = CallState.ENDED, sequence = 2)
        val stale = CallSyncReceipt(
            callId = call.callId,
            state = CallState.ENDED.name,
            stateSequence = 3,
            acknowledgedState = CallState.RINGING.name,
            acknowledgedStateSequence = 2,
            deduplicated = false,
        )

        val error = assertThrows(CommunicationException::class.java) {
            validateCallReceipt(call, stale)
        }

        assertEquals(true, error.retryable)
    }

    @Test
    fun acknowledgementForAnotherHistoricalSequenceCannotDeliverCurrentOutboxItem() {
        val call = call(state = CallState.ENDED, sequence = 2)
        val wrongSequence = CallSyncReceipt(
            callId = call.callId,
            state = CallState.ENDED.name,
            stateSequence = 3,
            acknowledgedState = CallState.ENDED.name,
            acknowledgedStateSequence = 3,
            deduplicated = true,
        )

        val error = assertThrows(CommunicationException::class.java) {
            validateCallReceipt(call, wrongSequence)
        }

        assertEquals(true, error.retryable)
    }

    @Test
    fun legacyConnectedSnapshotBridgesFromTheServerStateAndAdoptsServerSequence() {
        val call = call(state = CallState.CONNECTED, sequence = 9)
        val requestedStates = mutableListOf<CallState>()
        var serverSequence = 1L

        val receipt = reconcileLegacyCallWithRequest(call, wallClock = { 1_786_000_002_000 }) {
                _, path, body ->
            if (path == "/v1/calls") {
                receiptJson(
                    CallState.REQUESTED,
                    1,
                    acknowledgedState = CallState.REQUESTED,
                    acknowledgedSequence = 1,
                    deduplicated = true,
                )
            } else {
                val target = CallState.valueOf(body.getString("state"))
                requestedStates += target
                serverSequence += 1
                receiptJson(target, serverSequence)
            }
        }

        assertEquals(listOf(CallState.ACCEPTED, CallState.CONNECTED), requestedStates)
        assertEquals(CallState.CONNECTED.name, receipt.state)
        assertEquals(3L, receipt.stateSequence)
        assertEquals(3L, receipt.acknowledgedStateSequence)
    }

    @Test
    fun legacyStateOnlyDeliveryReconcilesBeforeItsNextTransitionIsSynced() {
        val legacySnapshot = call(state = CallState.CONNECTED, sequence = 4).copy(
            deliveryState = DeliveryState.DELIVERED,
        )
        val reconciled = reconcileLegacyCallWithRequest(
            legacySnapshot,
            wallClock = { 1_786_000_002_000 },
        ) { _, path, _ ->
            assertEquals("/v1/calls", path)
            // The v9 client accepted CONNECTED by state name and stored local sequence 4, while
            // the authoritative server transition was sequence 3.
            receiptJson(
                CallState.CONNECTED,
                3,
                acknowledgedState = CallState.REQUESTED,
                acknowledgedSequence = 1,
                deduplicated = true,
            )
        }
        assertEquals(3L, reconciled.stateSequence)

        val endedAfterReconciliation = legacySnapshot.copy(
            state = CallState.ENDED,
            stateSequence = reconciled.stateSequence + 1,
            updatedAtEpochMillis = 1_786_000_002_100,
            lastReason = "DEVICE_KEY_HANGUP",
            deliveryState = DeliveryState.PENDING,
        )
        val paths = mutableListOf<String>()
        val delivered = syncCallWithRequest(endedAfterReconciliation) { _, path, _ ->
            paths += path
            if (path == "/v1/calls") {
                receiptJson(
                    CallState.CONNECTED,
                    3,
                    acknowledgedState = CallState.REQUESTED,
                    acknowledgedSequence = 1,
                    deduplicated = true,
                )
            } else {
                receiptJson(CallState.ENDED, 4)
            }
        }

        assertEquals(listOf("/v1/calls", "/v1/calls/call-offline/transitions"), paths)
        assertEquals(CallState.ENDED.name, delivered.acknowledgedState)
        assertEquals(4L, delivered.acknowledgedStateSequence)
    }

    @Test
    fun confirmedSequenceFiveContinuesWithPreciselyAcknowledgedSequenceSix() {
        val call = call(state = CallState.ENDED, sequence = 6)

        val receipt = syncCallWithRequest(call) { _, path, _ ->
            if (path == "/v1/calls") {
                receiptJson(
                    CallState.CONNECTED,
                    5,
                    acknowledgedState = CallState.REQUESTED,
                    acknowledgedSequence = 1,
                    deduplicated = true,
                )
            } else {
                receiptJson(CallState.ENDED, 6)
            }
        }

        assertEquals(6L, receipt.acknowledgedStateSequence)
        assertEquals(CallState.ENDED.name, receipt.acknowledgedState)
    }

    @Test
    fun legacyReconciliationUsesExistingAdvancedOrTerminalServerStateWithoutBlindReplay() {
        val connected = reconcileLegacyCallWithRequest(
            call(CallState.CONNECTED, 9),
            wallClock = { 1_786_000_002_000 },
        ) { _, path, _ ->
            assertEquals("/v1/calls", path)
            receiptJson(
                CallState.CONNECTED,
                5,
                acknowledgedState = CallState.REQUESTED,
                acknowledgedSequence = 1,
                deduplicated = true,
            )
        }
        assertEquals(CallState.CONNECTED.name, connected.acknowledgedState)
        assertEquals(5L, connected.acknowledgedStateSequence)

        val ended = reconcileLegacyCallWithRequest(
            call(CallState.CONNECTING, 9),
            wallClock = { 1_786_000_002_000 },
        ) { _, path, _ ->
            assertEquals("/v1/calls", path)
            receiptJson(
                CallState.ENDED,
                6,
                acknowledgedState = CallState.REQUESTED,
                acknowledgedSequence = 1,
                deduplicated = true,
            )
        }
        assertEquals(CallState.ENDED.name, ended.acknowledgedState)
        assertEquals(6L, ended.acknowledgedStateSequence)
    }

    private fun commandJson(acknowledged: Boolean) = JSONObject()
        .put("commandId", "command-41")
        .put("deviceId", "device-a")
        .put("sequence", 41)
        .put("type", "TEXT_BROADCAST")
        .put("payload", JSONObject().put("broadcastId", "broadcast-41"))
        .put("createdAtEpochMillis", 1_786_000_000_000)
        .put("acknowledged", acknowledged)

    private fun call(state: CallState, sequence: Long) = CallSession(
        callId = "call-offline",
        deviceId = "device-a",
        direction = CallDirection.OUTGOING_DEVICE,
        mediaMode = CallMediaMode.VIDEO_UPLINK,
        state = state,
        stateSequence = sequence,
        createdAtEpochMillis = 1_786_000_000_000,
        updatedAtEpochMillis = 1_786_000_001_000,
        relatedEventId = "event-a",
        simulated = false,
        lastReason = "DEVICE_KEY_HANGUP",
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )

    private fun receiptJson(
        state: CallState,
        sequence: Long,
        acknowledgedState: CallState = state,
        acknowledgedSequence: Long = sequence,
        deduplicated: Boolean = false,
    ) = JSONObject()
        .put("callId", "call-offline")
        .put("state", state.name)
        .put("stateSequence", sequence)
        .put("acknowledgedState", acknowledgedState.name)
        .put("acknowledgedStateSequence", acknowledgedSequence)
        .put("deduplicated", deduplicated)
        .put("updatedAtEpochMillis", 1_786_000_001_000 + sequence)
}
