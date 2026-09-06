package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.TextBroadcast
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationStoreInstrumentedTest {
    private lateinit var database: HelmetDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HelmetDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun callLifecycleIsPersistentOrderedAndIdempotent() = runBlocking {
        var time = 100L
        val store = CallStore(database) { time++ }
        val created = requireNotNull(store.createOutgoing("device-1", "event-1", simulated = true, callId = "call-1"))
        assertEquals(CallState.REQUESTED, created.state)
        assertEquals(1, created.stateSequence)
        assertEquals(created, store.createOutgoing("device-1", "event-1", true, callId = "call-1"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.createOutgoing("device-1", null, true, callId = "call-1") }
        }
        assertFalse(store.createOutgoing("device-1", null, true, callId = "call-2") != null)
        assertEquals(CallState.RINGING, store.transition("call-1", CallState.RINGING).state)
        assertEquals(2L, store.find("call-1")?.stateSequence)
        assertEquals(2, store.transition("call-1", CallState.RINGING).stateSequence)
        assertEquals(CallState.ACCEPTED, store.transition("call-1", CallState.ACCEPTED).state)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.transition("call-1", CallState.REQUESTED) }
        }
        assertEquals(listOf(1L, 2L, 3L), store.deliveryHistory("call-1").map { it.stateSequence })
        assertTrue(store.markAttempt("call-1", 1, 200))
        assertTrue(store.markDelivered("call-1", 1, 201))
        assertTrue(store.markAttempt("call-1", 2, 202))
        assertTrue(store.markDelivered("call-1", 2, 203))
        assertTrue(store.markAttempt("call-1", 3, 204))
        assertTrue(store.markDelivered("call-1", 3, 205))
        assertEquals(DeliveryState.DELIVERED, store.find("call-1")?.deliveryState)
        assertEquals(1, store.find("call-1")?.attemptCount)
        assertEquals(CallState.ENDED, store.transition("call-1", CallState.ENDED, "REMOTE_HANGUP").state)
        assertEquals(0, store.find("call-1")?.attemptCount)
        val failedSync = store.find("call-1")
        assertTrue(store.markFailed("call-1", requireNotNull(failedSync).stateSequence, "network"))
        assertEquals("REMOTE_HANGUP", store.find("call-1")?.lastReason)
        assertEquals(DeliveryState.FAILED, store.find("call-1")?.deliveryState)
        assertEquals(failedSync.stateSequence, store.find("call-1")?.stateSequence)
        val terminalReplay = requireNotNull(
            store.createOutgoing("device-1", "event-1", true, callId = "call-1"),
        )
        assertEquals(CallState.ENDED, terminalReplay.state)
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM call_sessions WHERE callId = 'call-1'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        assertTrue(store.createOutgoing("device-1", null, false, callId = "call-2") != null)
    }

    @Test
    fun callOutboxRemainsSequenceOrderedWhenWallClockMovesBackward() = runBlocking {
        val times = ArrayDeque(listOf(1_000L, 900L, 800L, 700L))
        val store = CallStore(database) { times.removeFirst() }
        store.createOutgoing("device-1", null, false, callId = "clock-call")
        store.transition("clock-call", CallState.RINGING)
        store.transition("clock-call", CallState.ACCEPTED)
        store.transition("clock-call", CallState.ENDED)

        val history = store.deliveryHistory("clock-call")
        assertEquals(listOf(1L, 2L, 3L, 4L), history.map { it.stateSequence })
        assertEquals(listOf(1_000L, 1_001L, 1_002L, 1_003L), history.map { it.updatedAtEpochMillis })
        assertEquals(history.map { it.stateSequence }, store.pending().map { it.stateSequence })
    }

    @Test
    fun remoteCallTransitionPreservesServerSequenceTimeAndRejectsConflicts() = runBlocking {
        val store = CallStore(database) { 1_000L }
        store.createOutgoing("device-1", null, false, callId = "remote-call")

        val ringing = store.applyRemoteTransition(
            callId = "remote-call",
            target = CallState.RINGING,
            stateSequence = 2,
            occurredAtEpochMillis = 1_500L,
            reason = "DISPATCHER_RING",
        )
        assertEquals(2L, ringing.stateSequence)
        assertEquals(1_500L, ringing.updatedAtEpochMillis)
        assertEquals(DeliveryState.DELIVERED, ringing.deliveryState)
        assertEquals(
            ringing,
            store.applyRemoteTransition(
                "remote-call",
                CallState.RINGING,
                2,
                1_500L,
                "DISPATCHER_RING",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyRemoteTransition("remote-call", CallState.ACCEPTED, 2, 1_500L, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyRemoteTransition("remote-call", CallState.ACCEPTED, 4, 1_600L, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyRemoteTransition("remote-call", CallState.ACCEPTED, 3, 1_499L, null)
            }
        }
        Unit
    }

    @Test
    fun authoritativeRemoteTerminalSupersedesUndeliveredOptimisticStateAtSameSequence() = runBlocking {
        val store = CallStore(database) { 1_000L }
        store.createOutgoing("device-1", null, false, callId = "terminal-race")
        store.applyRemoteTransition(
            "terminal-race",
            CallState.ACCEPTED,
            2,
            1_500,
            "DISPATCHER_ACCEPTED",
        )
        val connecting = store.transition("terminal-race", CallState.CONNECTING)
        assertEquals(3L, connecting.stateSequence)
        assertEquals(DeliveryState.PENDING, connecting.deliveryState)

        val ended = store.applyRemoteTransition(
            "terminal-race",
            CallState.ENDED,
            3,
            1_600,
            "DISPATCHER_ENDED",
        )

        assertEquals(CallState.ENDED, ended.state)
        assertEquals(3L, ended.stateSequence)
        assertEquals(DeliveryState.DELIVERED, ended.deliveryState)
        val superseded = database.callStateOutboxDao().find("terminal-race", 3)
        assertEquals(CallState.CONNECTING.name, superseded?.state)
        assertEquals(DeliveryState.REJECTED.name, superseded?.deliveryState)
        assertEquals(CallStore.REMOTE_TERMINAL_SUPERSEDED_ERROR, superseded?.lastError)
    }

    @Test
    fun lateCallDeliveryCallbackCannotOverwriteAuthoritativeRemoteTerminal() = runBlocking {
        val store = CallStore(database) { 1_000L }
        store.createOutgoing("device-1", null, false, callId = "late-terminal-race")
        store.applyRemoteTransition("late-terminal-race", CallState.ACCEPTED, 2, 1_500, null)
        store.transition("late-terminal-race", CallState.CONNECTING)
        assertTrue(store.markAttempt("late-terminal-race", 3, 1_550))

        store.applyRemoteTransition(
            "late-terminal-race",
            CallState.ENDED,
            3,
            1_600,
            "DISPATCHER_ENDED",
        )

        assertFalse(store.markDelivered("late-terminal-race", 3, 1_700))
        assertEquals(CallState.ENDED, store.find("late-terminal-race")?.state)
        assertEquals(DeliveryState.DELIVERED, store.find("late-terminal-race")?.deliveryState)
        assertEquals(
            DeliveryState.REJECTED.name,
            database.callStateOutboxDao().find("late-terminal-race", 3)?.deliveryState,
        )
    }

    @Test
    fun sameSequenceNonTerminalOrDeliveredStateConflictRemainsRejected() = runBlocking {
        val store = CallStore(database) { 1_000L }
        store.createOutgoing("device-1", null, false, callId = "nonterminal-race")
        store.applyRemoteTransition("nonterminal-race", CallState.ACCEPTED, 2, 1_500, null)
        store.transition("nonterminal-race", CallState.CONNECTING)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyRemoteTransition("nonterminal-race", CallState.CONNECTED, 3, 1_600, null)
            }
        }

        assertTrue(store.markAttempt("nonterminal-race", 3, 1_650))
        assertTrue(store.markDelivered("nonterminal-race", 3, 1_700))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyRemoteTransition("nonterminal-race", CallState.ENDED, 3, 1_800, null)
            }
        }
        Unit
    }

    @Test
    fun callStateSequenceReachesMaximumThenFailsClosedWithoutWrapping() = runBlocking {
        val store = CallStore(database) { 2_000L }
        store.createOutgoing("device-1", null, false, callId = "max-sequence-call")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE call_sessions SET stateSequence = ? WHERE callId = ?",
            arrayOf(Long.MAX_VALUE - 1, "max-sequence-call"),
        )

        val maximum = store.transition("max-sequence-call", CallState.RINGING)
        assertEquals(Long.MAX_VALUE, maximum.stateSequence)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.transition("max-sequence-call", CallState.ACCEPTED) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.applyRemoteTransition(
                    "max-sequence-call",
                    CallState.ACCEPTED,
                    Long.MAX_VALUE - 1,
                    maximum.updatedAtEpochMillis + 1,
                    null,
                )
            }
        }
        assertEquals(Long.MAX_VALUE, store.find("max-sequence-call")?.stateSequence)
    }

    @Test
    fun callTransitionTimeReachesMaximumThenFailsClosedWithoutRepeatingTimestamp() = runBlocking {
        val store = CallStore(database) { 1L }
        store.createOutgoing("device-1", null, false, callId = "max-time-call")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE call_sessions SET updatedAtEpochMillis = ? WHERE callId = ?",
            arrayOf(Long.MAX_VALUE - 1, "max-time-call"),
        )

        val maximum = store.transition("max-time-call", CallState.RINGING)
        assertEquals(Long.MAX_VALUE, maximum.updatedAtEpochMillis)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.transition("max-time-call", CallState.ACCEPTED) }
        }
        assertEquals(CallState.RINGING, store.find("max-time-call")?.state)
    }

    @Test
    fun confirmedSequenceFiveSurvivesIntoTheNextLocalTransition() = runBlocking {
        var time = 1_000L
        val store = CallStore(database) { time++ }
        store.createOutgoing("device-1", null, false, callId = "confirmed-five")
        store.transition("confirmed-five", CallState.RINGING)
        store.transition("confirmed-five", CallState.ACCEPTED)
        store.transition("confirmed-five", CallState.CONNECTING)
        val connected = store.transition("confirmed-five", CallState.CONNECTED)
        assertEquals(5L, connected.stateSequence)
        assertTrue(store.markAttempt("confirmed-five", 5, 1_900))
        assertTrue(store.markDelivered("confirmed-five", 5, 2_000))

        val ended = store.transition("confirmed-five", CallState.ENDED)
        assertEquals(6L, ended.stateSequence)
    }

    @Test
    fun legacyStateOnlyDeliveryReconcilesServerSequenceBeforeTheNextTransition() = runBlocking {
        var time = 1_000L
        val store = CallStore(database) { time++ }
        store.createOutgoing("device-1", null, false, callId = "legacy-state-only-delivery")
        store.transition("legacy-state-only-delivery", CallState.ACCEPTED)
        store.transition("legacy-state-only-delivery", CallState.CONNECTING)
        val localConnected = store.transition("legacy-state-only-delivery", CallState.CONNECTED)
        assertEquals(4L, localConnected.stateSequence)
        assertTrue(store.markAttempt("legacy-state-only-delivery", 4, 1_900))
        assertTrue(store.markDelivered("legacy-state-only-delivery", 4, 2_000))

        // v9 treated a matching state name as delivered even when the backend had CONNECTED at
        // sequence 3. Migration keeps this snapshot only as a pending reconciliation input.
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM call_state_outbox WHERE callId = ?",
            arrayOf("legacy-state-only-delivery"),
        )
        database.callStateOutboxDao().replace(
            CallStateOutboxEntity(
                callId = "legacy-state-only-delivery",
                stateSequence = 4,
                state = CallState.CONNECTED.name,
                actorId = "device-1",
                occurredAtEpochMillis = localConnected.updatedAtEpochMillis,
                reason = null,
                deliveryState = DeliveryState.PENDING.name,
                attemptCount = 1,
                lastAttemptAtEpochMillis = 1_900,
                deliveredAtEpochMillis = null,
                lastError = CallStore.MIGRATED_V9_RECONCILIATION_MARKER,
            ),
        )

        val reconciled = store.completeLegacyReconciliation(
            callId = "legacy-state-only-delivery",
            expectedLocalSequence = 4,
            serverState = CallState.CONNECTED,
            serverSequence = 3,
            serverUpdatedAtEpochMillis = 2_100,
            deliveredAtEpochMillis = 2_200,
        )

        assertEquals(3L, reconciled.stateSequence)
        assertEquals(DeliveryState.DELIVERED, reconciled.deliveryState)
        assertEquals(null, database.callStateOutboxDao().find("legacy-state-only-delivery", 4))
        val ended = store.transition("legacy-state-only-delivery", CallState.ENDED)
        assertEquals(4L, ended.stateSequence)
        assertEquals(CallState.ENDED.name, database.callStateOutboxDao().find(ended.callId, 4)?.state)
    }

    @Test
    fun uncertainLegacySnapshotRebasesToTheServerSequenceAtomically() = runBlocking {
        var time = 1_000L
        val store = CallStore(database) { time++ }
        store.createOutgoing("device-1", null, false, callId = "legacy-rebase")
        store.transition("legacy-rebase", CallState.ACCEPTED)
        store.transition("legacy-rebase", CallState.CONNECTING)
        store.transition("legacy-rebase", CallState.CONNECTED)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE call_sessions SET stateSequence = 9, deliveryState = 'FAILED' WHERE callId = ?",
            arrayOf("legacy-rebase"),
        )
        database.callStateOutboxDao().insert(
            CallStateOutboxEntity(
                callId = "legacy-rebase",
                stateSequence = 9,
                state = CallState.CONNECTED.name,
                actorId = "device-1",
                occurredAtEpochMillis = 1_900,
                reason = null,
                deliveryState = DeliveryState.FAILED.name,
                attemptCount = 3,
                lastAttemptAtEpochMillis = 1_850,
                deliveredAtEpochMillis = null,
                lastError = CallStore.MIGRATED_V9_RECONCILIATION_MARKER,
            ),
        )

        val reconciled = store.completeLegacyReconciliation(
            callId = "legacy-rebase",
            expectedLocalSequence = 9,
            serverState = CallState.CONNECTED,
            serverSequence = 4,
            serverUpdatedAtEpochMillis = 2_000,
            deliveredAtEpochMillis = 2_100,
        )

        assertEquals(4L, reconciled.stateSequence)
        assertEquals(DeliveryState.DELIVERED, reconciled.deliveryState)
        assertEquals(null, database.callStateOutboxDao().find("legacy-rebase", 9))
        assertEquals(
            DeliveryState.DELIVERED.name,
            database.callStateOutboxDao().find("legacy-rebase", 4)?.deliveryState,
        )
        val ended = store.transition("legacy-rebase", CallState.ENDED)
        assertEquals(5L, ended.stateSequence)
    }

    @Test
    fun callMediaCheckpointAdvancesAndRejectsStaleProcessGeneration() = runBlocking {
        var time = 1_000L
        val store = CallMediaRecoveryStore(database) { time++ }
        val first = store.begin("call-media")
        assertEquals(1L, first.generation)
        assertFalse(first.recoveredFromPreviousProcess)
        assertFalse(first.awaitingAnswer)

        val offered = store.recordOffer("call-media", first.generation, 4)
        assertTrue(offered.awaitingAnswer)
        store.advanceRemote("call-media", first.generation, 5)
        val answered = store.recordAnswer("call-media", first.generation, 5)
        assertFalse(answered.awaitingAnswer)
        assertEquals(4L, answered.latestAnsweredOfferSequence)

        val recovered = store.begin("call-media")
        assertEquals(2L, recovered.generation)
        assertTrue(recovered.recoveredFromPreviousProcess)
        assertEquals(5L, recovered.lastRemoteSequence)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.recordRestart("call-media", first.generation) }
        }
        assertEquals(1, store.recordRestart("call-media", recovered.generation).restartAttemptCount)
        assertTrue(store.clear("call-media"))
        assertEquals(null, store.find("call-media"))
    }

    @Test
    fun hardwareKeyPlanSurvivesCrashAndDuplicateReceiptWithoutReplanning() = runBlocking {
        var time = 1_000L
        val store = HardwareKeyActionStore(database) { time++ }
        val planned = HardwareKeyAction(
            actionId = "device:key:CALL:77",
            input = "CALL",
            plannedAction = "CALL_START",
            plannedArgument = "START_TRANSMIT",
            businessRequestId = 91,
            rejectionReason = null,
            targetCallId = null,
            simulated = false,
            monotonicMillis = 77,
            hardwareEventId = 77,
            sequence = 9,
            receivedAtEpochMillis = 999,
        )

        assertEquals(planned, store.receive(planned))
        assertEquals(listOf(planned), store.pending())
        assertEquals(planned, store.receive(planned))
        assertEquals(
            planned,
            store.receive(planned.copy(sequence = 19, receivedAtEpochMillis = 1_500)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.receive(planned.copy(businessRequestId = 92)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.receive(
                    planned.copy(
                        input = "SOS",
                        plannedAction = "SOS",
                        plannedArgument = null,
                        businessRequestId = null,
                    ),
                )
            }
        }
        assertEquals(1, store.pending().size)
        assertTrue(store.markApplied(planned.actionId))
        assertEquals(HardwareKeyActionState.APPLIED, store.find(planned.actionId)?.state)
        assertTrue(store.pending().isEmpty())
        assertFalse(store.markFailed(planned.actionId, "late failure"))
    }

    @Test
    fun retryableHardwareKeyFailureKeepsTheExactPlanPending() = runBlocking {
        val store = HardwareKeyActionStore(database) { 2_100L }
        val planned = HardwareKeyAction(
            actionId = "device:key:CALL:91",
            input = "CALL",
            plannedAction = "LOCAL_INTERCOM_COMMAND",
            plannedArgument = "START_TRANSMIT",
            businessRequestId = 0x1020_3040,
            rejectionReason = null,
            targetCallId = null,
            simulated = false,
            monotonicMillis = 91,
            hardwareEventId = 91,
            sequence = 11,
            receivedAtEpochMillis = 2_000,
        )
        store.receive(planned)

        assertTrue(store.recordRetryableFailure(planned.actionId, "HardwareCommandException"))

        val recovered = requireNotNull(store.find(planned.actionId))
        assertEquals(HardwareKeyActionState.RECEIVED, recovered.state)
        assertEquals(1, recovered.attemptCount)
        assertEquals(planned.plannedArgument, recovered.plannedArgument)
        assertEquals(planned.businessRequestId, recovered.businessRequestId)
        assertEquals(listOf(planned.actionId), store.pending().map { it.actionId })
    }

    @Test
    fun failedHardwareKeyActionIsTerminalAndDoesNotBlockOtherPendingActions() = runBlocking {
        val store = HardwareKeyActionStore(database) { 2_000L }
        fun action(id: String) = HardwareKeyAction(
            actionId = id,
            input = "RECORD_LONG",
            plannedAction = "VIDEO_STOP",
            rejectionReason = null,
            targetCallId = null,
            simulated = false,
            monotonicMillis = 88,
            hardwareEventId = id.last().digitToInt().toLong(),
            sequence = 10,
            receivedAtEpochMillis = 1_999,
        )
        val first = action("device:key:RECORD_LONG:1")
        val second = action("device:key:RECORD_LONG:2")
        store.receive(first)
        store.receive(second)

        assertTrue(store.markFailed(first.actionId, "CameraOperationException"))

        assertEquals(HardwareKeyActionState.FAILED, store.find(first.actionId)?.state)
        assertEquals(listOf(second.actionId), store.pending().map { it.actionId })
    }

    @Test
    fun broadcastPersistsPlaybackAndReceiptStates() = runBlocking {
        val store = BroadcastStore(database)
        val message = TextBroadcast(
            broadcastId = "broadcast-1",
            deviceId = "device-1",
            serverSequence = 1,
            text = "请立即撤离",
            language = "zh-CN",
            priority = 10,
            expiresAtEpochMillis = 10_000,
            playbackState = BroadcastPlaybackState.RECEIVED,
            receivedAtEpochMillis = 100,
            playingAtEpochMillis = null,
            playedAtEpochMillis = null,
            lastError = null,
            receiptDeliveryState = DeliveryState.PENDING,
            receiptAttemptCount = 0,
        )
        assertTrue(store.receive(STREAM_A, message))
        assertFalse(store.receive(STREAM_A, message))
        assertTrue(store.updatePlayback(STREAM_A, "device-1", "broadcast-1", BroadcastPlaybackState.PLAYING, 200))
        assertTrue(
            store.updatePlayback(
                STREAM_A,
                "device-1",
                "broadcast-1",
                BroadcastPlaybackState.FAILED,
                300,
                "TTS_FAILURE",
            ),
        )
        assertEquals(BroadcastPlaybackState.FAILED, store.find(STREAM_A, "broadcast-1")?.playbackState)
        assertTrue(store.markReceiptAttempt(STREAM_A, "device-1", "broadcast-1", 400))
        assertTrue(store.markReceiptFailed(STREAM_A, "device-1", "broadcast-1", "network timeout"))
        assertEquals("TTS_FAILURE", store.find(STREAM_A, "broadcast-1")?.lastError)
        database.queryString(
            "SELECT receiptLastError FROM text_broadcasts " +
                "WHERE commandStreamId = ? AND broadcastId = ?",
            STREAM_A,
            "broadcast-1",
        ).let { assertEquals("network timeout", it) }
        assertTrue(store.markReceiptAttempt(STREAM_A, "device-1", "broadcast-1", 450))
        database.queryString(
            "SELECT receiptLastError FROM text_broadcasts " +
                "WHERE commandStreamId = ? AND broadcastId = ?",
            STREAM_A,
            "broadcast-1",
        ).let { assertEquals(null, it) }
        assertTrue(store.markReceiptDelivered(STREAM_A, "device-1", "broadcast-1", 500))
        assertEquals(DeliveryState.DELIVERED, store.find(STREAM_A, "broadcast-1")?.receiptDeliveryState)

        val rejected = message.copy(broadcastId = "broadcast-2", serverSequence = 2)
        assertTrue(store.receive(STREAM_A, rejected))
        assertTrue(store.markReceiptAttempt(STREAM_A, "device-1", rejected.broadcastId, 600))
        assertTrue(store.markReceiptRejected(STREAM_A, "device-1", rejected.broadcastId, "HTTP 422"))
        assertEquals(DeliveryState.REJECTED, store.find(STREAM_A, rejected.broadcastId)?.receiptDeliveryState)
        assertEquals(null, store.find(STREAM_A, rejected.broadcastId)?.lastError)
        assertEquals(
            "HTTP 422",
            database.queryString(
                "SELECT receiptLastError FROM text_broadcasts " +
                    "WHERE commandStreamId = ? AND broadcastId = ?",
                STREAM_A,
                rejected.broadcastId,
            ),
        )
        assertFalse(store.pendingReceipts(STREAM_A, "device-1").any { it.broadcastId == rejected.broadcastId })

        val anotherStream = message.copy(broadcastId = "broadcast-stream-b")
        assertTrue(store.receive(STREAM_B, anotherStream))
        assertEquals(listOf(anotherStream.broadcastId), store.pendingReceipts(STREAM_B, "device-1").map {
            it.broadcastId
        })
        assertTrue(store.pendingReceipts(STREAM_A, "device-1").none { it.broadcastId == anotherStream.broadcastId })
    }

    @Test
    fun broadcastTerminalPlaybackAndRejectedReceiptCannotBeResurrected() = runBlocking {
        val store = BroadcastStore(database)
        val message = TextBroadcast(
            broadcastId = "broadcast-terminal",
            deviceId = "device-1",
            serverSequence = 1,
            text = "立即撤离",
            language = "zh-CN",
            priority = 10,
            expiresAtEpochMillis = null,
            playbackState = BroadcastPlaybackState.RECEIVED,
            receivedAtEpochMillis = 100,
            playingAtEpochMillis = null,
            playedAtEpochMillis = null,
            lastError = null,
            receiptDeliveryState = DeliveryState.PENDING,
            receiptAttemptCount = 0,
        )
        assertTrue(store.receive(STREAM_A, message))
        assertTrue(store.markReceiptAttempt(STREAM_A, "device-1", message.broadcastId, 110))
        assertTrue(store.markReceiptRejected(STREAM_A, "device-1", message.broadcastId, "HTTP 422"))
        assertTrue(
            store.updatePlayback(
                STREAM_A,
                "device-1",
                message.broadcastId,
                BroadcastPlaybackState.PLAYING,
                200,
            ),
        )
        assertTrue(
            store.updatePlayback(
                STREAM_A,
                "device-1",
                message.broadcastId,
                BroadcastPlaybackState.FAILED,
                300,
                "TTS_UNAVAILABLE",
            ),
        )

        assertFalse(
            store.updatePlayback(
                STREAM_A,
                "device-1",
                message.broadcastId,
                BroadcastPlaybackState.PLAYING,
                400,
            ),
        )
        assertFalse(
            store.updatePlayback(
                STREAM_A,
                "device-1",
                message.broadcastId,
                BroadcastPlaybackState.PLAYED,
                400,
            ),
        )
        assertFalse(store.markReceiptDelivered(STREAM_A, "device-1", message.broadcastId, 500))
        val stored = requireNotNull(store.find(STREAM_A, message.broadcastId))
        assertEquals(BroadcastPlaybackState.FAILED, stored.playbackState)
        assertEquals(DeliveryState.REJECTED, stored.receiptDeliveryState)
        assertEquals("TTS_UNAVAILABLE", stored.lastError)
    }

    @Test
    fun commandIsStoredBeforeApplicationAndAck() = runBlocking {
        val store = DeviceCommandStore(database)
        val command = DeviceCommand(
            commandId = "command-1",
            deviceId = "device-1",
            serverSequence = 1,
            type = DeviceCommandType.CALL_STATE,
            payloadJson = "{\"callId\":\"call-1\",\"state\":\"RINGING\"}",
            createdAtEpochMillis = 100,
            state = DeviceCommandState.RECEIVED,
            receivedAtEpochMillis = 101,
            appliedAtEpochMillis = null,
            lastError = null,
            ackDeliveryState = DeliveryState.PENDING,
            ackAttemptCount = 0,
        )
        assertTrue(store.receive(STREAM_A, command))
        assertFalse(store.receive(STREAM_A, command))
        assertEquals(1, store.maxSequence(STREAM_A, "device-1"))
        assertEquals(listOf("command-1"), store.pendingApplication(STREAM_A, "device-1").map { it.commandId })
        assertTrue(store.markApplied(STREAM_A, "command-1", 200))
        assertEquals(DeviceCommandState.APPLIED, store.find(STREAM_A, "command-1")?.state)
        assertTrue(store.markAckAttempt(STREAM_A, "command-1", 300))
        assertTrue(store.markAckDelivered(STREAM_A, "command-1", 400))
        assertEquals(DeliveryState.DELIVERED, store.find(STREAM_A, "command-1")?.ackDeliveryState)

        val rejected = command.copy(commandId = "command-2", serverSequence = 2)
        assertTrue(store.receive(STREAM_A, rejected))
        assertTrue(store.markApplied(STREAM_A, rejected.commandId, 500))
        assertTrue(store.markAckAttempt(STREAM_A, rejected.commandId, 600))
        assertTrue(store.markAckRejected(STREAM_A, rejected.commandId, "HTTP 422"))
        assertEquals(DeliveryState.REJECTED, store.find(STREAM_A, rejected.commandId)?.ackDeliveryState)
        assertEquals(null, store.find(STREAM_A, rejected.commandId)?.lastError)
        assertEquals(
            "HTTP 422",
            database.queryString(
                "SELECT ackLastError FROM device_commands " +
                    "WHERE commandStreamId = ? AND commandId = ?",
                STREAM_A,
                rejected.commandId,
            ),
        )
        assertFalse(store.pendingAcks(STREAM_A, "device-1").any { it.commandId == rejected.commandId })

        val failed = command.copy(commandId = "command-3", serverSequence = 3)
        assertTrue(store.receive(STREAM_A, failed))
        assertTrue(store.markFailed(STREAM_A, failed.commandId, 700, "EXECUTION_FAILURE"))
        assertTrue(store.markAckAttempt(STREAM_A, failed.commandId, 800))
        assertTrue(store.markAckFailed(STREAM_A, failed.commandId, "HTTP 503"))
        assertEquals("EXECUTION_FAILURE", store.find(STREAM_A, failed.commandId)?.lastError)
        assertEquals(
            "HTTP 503",
            database.queryString(
                "SELECT ackLastError FROM device_commands " +
                    "WHERE commandStreamId = ? AND commandId = ?",
                STREAM_A,
                failed.commandId,
            ),
        )
    }

    private fun HelmetDatabase.queryString(sql: String, vararg arguments: String): String? =
        openHelper.readableDatabase.query(sql, arguments).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    companion object {
        private const val STREAM_A = "00000000-0000-0000-0000-000000000001"
        private const val STREAM_B = "00000000-0000-0000-0000-000000000002"
    }
}
