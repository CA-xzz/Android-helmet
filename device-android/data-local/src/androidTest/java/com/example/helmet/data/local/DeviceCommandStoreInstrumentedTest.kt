package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.core.model.TextBroadcast
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCommandStoreInstrumentedTest {
    @Test
    fun pendingAcknowledgementsIncludeAppliedCommandsButExcludeUnappliedCommands() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val commandStreamId = UUID.randomUUID().toString()
        val deviceId = "mqtt-test-$nonce"
        val applied = command("applied-$nonce", deviceId, 1)
        val received = command("received-$nonce", deviceId, 2)
        assertTrue(store.receive(commandStreamId, applied))
        assertTrue(store.receive(commandStreamId, received))
        assertTrue(store.markApplied(commandStreamId, applied.commandId, System.currentTimeMillis()))

        val pending = store.pendingAcks(commandStreamId, deviceId, 100)
        assertEquals(listOf(applied.commandId), pending.map(DeviceCommand::commandId))
        assertFalse(pending.any { it.commandId == received.commandId })

        assertTrue(store.markAckAttempt(commandStreamId, applied.commandId, System.currentTimeMillis()))
        assertTrue(store.markAckDelivered(commandStreamId, applied.commandId, System.currentTimeMillis()))
        assertTrue(store.pendingAcks(commandStreamId, deviceId, 100).none { it.commandId == applied.commandId })
    }

    @Test
    fun appliedCommandCannotBeDowngradedAndTerminalAcknowledgementCannotBeReopened() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val commandStreamId = UUID.randomUUID().toString()
        val deviceId = "terminal-command-$nonce"
        val command = command("terminal-$nonce", deviceId, 1)
        assertTrue(store.receive(commandStreamId, command))
        assertTrue(store.markApplied(commandStreamId, command.commandId, command.receivedAtEpochMillis + 1))

        assertFalse(
            store.markFailed(
                commandStreamId,
                command.commandId,
                command.receivedAtEpochMillis + 2,
                "late failure",
            ),
        )
        assertEquals(DeviceCommandState.APPLIED, store.find(commandStreamId, command.commandId)?.state)

        assertTrue(store.markAckAttempt(commandStreamId, command.commandId, command.receivedAtEpochMillis + 3))
        assertTrue(store.markAckRejected(commandStreamId, command.commandId, "permanent rejection"))
        assertFalse(store.markAckDelivered(commandStreamId, command.commandId, command.receivedAtEpochMillis + 4))
        assertEquals(DeliveryState.REJECTED, store.find(commandStreamId, command.commandId)?.ackDeliveryState)
    }

    @Test
    fun acknowledgedServerHistoryAdvancesCursorButIsNeverAppliedOrAcknowledgedAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val commandStreamId = UUID.randomUUID().toString()
        val deviceId = "history-test-$nonce"
        val acknowledged = command("acknowledged-$nonce", deviceId, 41).copy(
            state = DeviceCommandState.ACKNOWLEDGED,
            ackDeliveryState = DeliveryState.DELIVERED,
        )

        repeat(40) { index ->
            val history = command("history-$index-$nonce", deviceId, index + 1L).copy(
                state = DeviceCommandState.ACKNOWLEDGED,
                ackDeliveryState = DeliveryState.DELIVERED,
            )
            assertTrue(store.receive(commandStreamId, history))
        }
        assertTrue(store.receive(commandStreamId, acknowledged))
        assertEquals(41, store.maxSequence(commandStreamId, deviceId))
        assertFalse(store.pendingApplication(commandStreamId, deviceId, 100).any {
            it.commandId == acknowledged.commandId
        })
        assertFalse(store.pendingAcks(commandStreamId, deviceId, 100).any {
            it.commandId == acknowledged.commandId
        })
        assertEquals(DeviceCommandState.ACKNOWLEDGED, store.find(commandStreamId, acknowledged.commandId)?.state)
        assertEquals(DeliveryState.DELIVERED, store.find(commandStreamId, acknowledged.commandId)?.ackDeliveryState)
    }

    @Test
    fun commandStreamsIsolateSequencesQueuesAndGlobalCommandIds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val nonce = UUID.randomUUID().toString()
        val streamA = UUID.randomUUID().toString()
        val streamB = UUID.randomUUID().toString()
        val streamC = UUID.randomUUID().toString()
        val deviceId = "scope-test-$nonce"
        val firstA = command("scope-a-1-$nonce", deviceId, 1)
        val firstB = command("scope-b-1-$nonce", deviceId, 1)

        assertTrue(store.receive(streamA, firstA))
        assertFalse(store.receive(streamA, firstA))
        assertTrue(store.receive(streamB, firstB))
        assertEquals(1, store.maxSequence(streamA, deviceId))
        assertEquals(1, store.maxSequence(streamB, deviceId))
        assertEquals(listOf(firstA.commandId), store.pendingApplication(streamA, deviceId).map {
            it.commandId
        })
        assertEquals(listOf(firstB.commandId), store.pendingApplication(streamB, deviceId).map {
            it.commandId
        })

        assertTrue(store.markApplied(streamA, firstA.commandId, firstA.receivedAtEpochMillis + 1))
        assertEquals(1, store.pendingAckCount(streamA, deviceId))
        assertEquals(0, store.pendingAckCount(streamB, deviceId))
        assertEquals(1, store.pendingApplicationCount(streamB, deviceId))
        assertEquals(0, store.pendingApplicationCount(streamA, deviceId))

        assertTrue(store.receive(streamC, firstA))
        assertEquals(1, store.maxSequence(streamC, deviceId))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.receive(streamA, command("other-a-1-$nonce", deviceId, 1)) }
        }
        assertEquals(1, store.maxSequence(streamA, deviceId))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.receive(UUID.randomUUID().toString(), command("gap-$nonce", deviceId, 2)) }
        }

        val secondA = command("scope-a-2-$nonce", deviceId, 2)
        assertTrue(store.receive(streamA, secondA))
        assertEquals(2, store.maxSequence(streamA, deviceId))
        assertEquals(1, store.maxSequence(streamB, deviceId))
    }

    @Test
    fun rotatedStreamCopiesPrefixAndMergesAcknowledgementsWithoutMutatingSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val nonce = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "adoption-state-$nonce"
        val broadcastId = "broadcast-$nonce"
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "restore receipt")
            .put("language", "en-US")
            .put("priority", 2)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val first = command("first-$nonce", deviceId, 1).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
        )
        val second = command("second-$nonce", deviceId, 2)
        val third = command("third-$nonce", deviceId, 3)
        listOf(first, second, third).forEach { assertTrue(store.receive(source, it)) }
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(store.markApplied(source, first.commandId, first.receivedAtEpochMillis + 1L))
        assertTrue(store.markAckDelivered(source, first.commandId, first.receivedAtEpochMillis + 2L))
        assertTrue(store.markFailed(source, third.commandId, third.receivedAtEpochMillis + 1L, "failed"))
        assertTrue(store.markAckRejected(source, third.commandId, "old rejection"))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "restore receipt",
                    "en-US",
                    2,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    first.receivedAtEpochMillis,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYED,
                first.receivedAtEpochMillis + 1L,
            ),
        )
        assertTrue(
            broadcasts.markReceiptAttempt(
                source,
                deviceId,
                broadcastId,
                first.receivedAtEpochMillis + 2L,
            ),
        )
        assertTrue(
            broadcasts.markReceiptDelivered(
                source,
                deviceId,
                broadcastId,
                first.receivedAtEpochMillis + 2L,
            ),
        )
        val authoritative = listOf(
            first.copy(state = DeviceCommandState.ACKNOWLEDGED, ackDeliveryState = DeliveryState.DELIVERED),
            second.copy(state = DeviceCommandState.ACKNOWLEDGED, ackDeliveryState = DeliveryState.DELIVERED),
            third,
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 3L, authoritative.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 3L, authoritative),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(target, deviceId),
        )

        assertEquals(3L, store.maxSequence(source, deviceId))
        assertEquals(3L, store.maxSequence(target, deviceId))
        assertEquals(DeliveryState.DELIVERED, store.find(source, first.commandId)?.ackDeliveryState)
        assertEquals(DeviceCommandState.APPLIED, store.find(target, first.commandId)?.state)
        assertEquals(DeliveryState.DELIVERED, store.find(target, first.commandId)?.ackDeliveryState)
        assertEquals(DeviceCommandState.ACKNOWLEDGED, store.find(target, second.commandId)?.state)
        assertEquals(DeviceCommandState.FAILED, store.find(target, third.commandId)?.state)
        assertEquals(DeliveryState.PENDING, store.find(target, third.commandId)?.ackDeliveryState)
        assertEquals(DeliveryState.DELIVERED, broadcasts.find(source, broadcastId)?.receiptDeliveryState)
        assertEquals(DeliveryState.PENDING, broadcasts.find(target, broadcastId)?.receiptDeliveryState)
        assertEquals(BroadcastPlaybackState.PLAYED, broadcasts.find(target, broadcastId)?.playbackState)
    }

    @Test
    fun acknowledgedBroadcastAdoptionReplaysReceiptWithoutReapplyingPlayback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "acknowledged-broadcast-${UUID.randomUUID()}"
        val broadcastId = "broadcast-${UUID.randomUUID()}"
        val eventTime = System.currentTimeMillis()
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "already played")
            .put("language", "en-US")
            .put("priority", 2)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val acknowledged = command("command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
            state = DeviceCommandState.ACKNOWLEDGED,
            ackDeliveryState = DeliveryState.DELIVERED,
        )
        assertTrue(commands.receive(source, acknowledged))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "already played",
                    "en-US",
                    2,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    eventTime,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYING,
                eventTime + 1L,
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYED,
                eventTime + 2L,
            ),
        )
        assertTrue(broadcasts.markReceiptAttempt(source, deviceId, broadcastId, eventTime + 3L))
        assertTrue(broadcasts.markReceiptDelivered(source, deviceId, broadcastId, eventTime + 3L))

        adopt(commands, target, deviceId, listOf(acknowledged))

        assertEquals(0, commands.pendingApplicationCount(target, deviceId))
        assertEquals(0, commands.pendingAckCount(target, deviceId))
        assertEquals(1, broadcasts.pendingReceiptCount(target, deviceId))
        val replay = broadcasts.pendingReceipts(target, deviceId).single()
        assertEquals(BroadcastPlaybackState.PLAYED, replay.playbackState)
        assertEquals(eventTime + 1L, replay.playingAtEpochMillis)
        assertEquals(eventTime + 2L, replay.playedAtEpochMillis)
        assertEquals(DeliveryState.PENDING, replay.receiptDeliveryState)
        assertEquals(DeliveryState.DELIVERED, broadcasts.find(source, broadcastId)?.receiptDeliveryState)
    }

    @Test
    fun pendingBroadcastReceiptIsIdempotentlyResetDuringAdoption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "pending-broadcast-${UUID.randomUUID()}"
        val broadcastId = "broadcast-${UUID.randomUUID()}"
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "pending")
            .put("language", "en-US")
            .put("priority", 1)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val command = command("command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
        )
        assertTrue(commands.receive(source, command))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "pending",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    command.receivedAtEpochMillis,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )

        adopt(commands, target, deviceId, listOf(command))

        assertEquals(DeliveryState.PENDING, broadcasts.find(target, broadcastId)?.receiptDeliveryState)
        assertEquals(1, broadcasts.pendingReceiptCount(target, deviceId))
    }

    @Test
    fun playedBroadcastRotationClearsReceiptErrorWithoutCreatingPlaybackError() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "played-rejected-broadcast-${UUID.randomUUID()}"
        val broadcastId = "broadcast-${UUID.randomUUID()}"
        val eventTime = System.currentTimeMillis()
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "played")
            .put("language", "en-US")
            .put("priority", 1)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val acknowledged = command("command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
            state = DeviceCommandState.ACKNOWLEDGED,
            ackDeliveryState = DeliveryState.DELIVERED,
        )
        assertTrue(commands.receive(source, acknowledged))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "played",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    eventTime,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYED,
                eventTime + 1L,
            ),
        )
        assertTrue(broadcasts.markReceiptRejected(source, deviceId, broadcastId, "HTTP 422"))

        adopt(commands, target, deviceId, listOf(acknowledged))

        val replay = requireNotNull(broadcasts.find(target, broadcastId))
        assertEquals(BroadcastPlaybackState.PLAYED, replay.playbackState)
        assertEquals(null, replay.lastError)
        assertEquals(DeliveryState.PENDING, replay.receiptDeliveryState)
        database.openHelper.readableDatabase.query(
            "SELECT receiptLastError FROM text_broadcasts " +
                "WHERE commandStreamId = ? AND broadcastId = ?",
            arrayOf(target, broadcastId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun stagingResumesPastOneThousandRowsWhenBackendHighWaterGrows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "adoption-resume-${UUID.randomUUID()}"
        val history = (1L..1_001L).map { sequence ->
            command("resume-$sequence-$deviceId", deviceId, sequence)
        }
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        var store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_500L, history.first()),
        )
        history.take(1_000).chunked(100).forEach { page ->
            assertEquals(
                DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
                store.stageStreamAdoptionPage(target, deviceId, 1_001L, page),
            )
        }

        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 1_001L, listOf(history.last())),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertEquals(1_001L, store.maxSequence(source, deviceId))
        assertEquals(1_001L, store.maxSequence(target, deviceId))
    }

    @Test
    fun divergenceInsidePageCommitsOnlyTheMatchingPrefix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "adoption-fork-${UUID.randomUUID()}"
        val history = (1L..142L).map { sequence ->
            command("source-$sequence-$deviceId", deviceId, sequence)
        }
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val divergent = command("target-42-$deviceId", deviceId, 42L)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 142L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 142L, history.take(41) + divergent),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertEquals(41L, store.maxSequence(target, deviceId))
        assertEquals(142L, store.maxSequence(source, deviceId))
        assertTrue(store.receive(target, divergent))
        assertEquals(42L, store.maxSequence(target, deviceId))
    }

    @Test
    fun stagedAdoptionRejectsSourceMutationWithoutCreatingTargetHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "adoption-mutation-${UUID.randomUUID()}"
        val history = listOf(command("immutable-$deviceId", deviceId, 1L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 1L, history),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE device_commands SET payloadJson = '{\"changed\":true}' " +
                "WHERE commandStreamId = ? AND commandId = ?",
            arrayOf(source, history.first().commandId),
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
        assertEquals(source, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
    }

    @Test
    fun stagedAdoptionCannotCommitAfterAnotherStreamBecomesActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val replacement = UUID.randomUUID().toString()
        val deviceId = "adoption-active-change-${UUID.randomUUID()}"
        val history = listOf(command("active-$deviceId", deviceId, 1L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 1L, history),
        )
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, replacement))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
        assertEquals(replacement, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
    }

    @Test
    fun executedBroadcastWithoutDurablePlaybackRowCannotBeAdopted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "adoption-missing-broadcast-${UUID.randomUUID()}"
        val command = command("broadcast-command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = JSONObject()
                .put("broadcastId", "missing-$deviceId")
                .put("text", "missing")
                .put("language", "en-US")
                .put("priority", 1)
                .put("expiresAtEpochMillis", JSONObject.NULL)
                .toString(),
        )
        seedStream(database, source, deviceId, listOf(command))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(store.markApplied(source, command.commandId, command.receivedAtEpochMillis + 1L))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, command),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 1L, listOf(command)),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun repeatedRotationsUseTheActiveSourceAndExistingStreamCanBeReactivated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val streamA = UUID.randomUUID().toString()
        val streamB = UUID.randomUUID().toString()
        val streamC = UUID.randomUUID().toString()
        val deviceId = "repeated-rotation-${UUID.randomUUID()}"
        val history = listOf(command("one-$deviceId", deviceId, 1), command("two-$deviceId", deviceId, 2))
        seedStream(database, streamA, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, streamA))
        adopt(store, streamB, deviceId, history)
        adopt(store, streamC, deviceId, history)
        assertEquals(streamC, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.ALREADY_INITIALIZED,
            store.prepareStreamAdoption(streamA, deviceId, 2L, history.first()),
        )
        assertEquals(streamA, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
    }

    @Test
    fun restoredStreamUsesUniqueMatchingHistoryInsteadOfUnrelatedActiveStream() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val streamA = UUID.randomUUID().toString()
        val unrelatedB = UUID.randomUUID().toString()
        val restoredC = UUID.randomUUID().toString()
        val deviceId = "restored-history-${UUID.randomUUID()}"
        val historyA = listOf(
            command("a-one-$deviceId", deviceId, 1L),
            command("a-two-$deviceId", deviceId, 2L),
        )
        seedStream(database, streamA, deviceId, historyA)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, streamA))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(
                unrelatedB,
                deviceId,
                1L,
                command("b-one-$deviceId", deviceId, 1L),
            ),
        )
        assertEquals(unrelatedB, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(restoredC, deviceId, 2L, historyA.first()),
        )
        assertEquals(streamA, store.streamAdoptionProgress(restoredC, deviceId)?.sourceStreamId)
        assertEquals(streamA, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(restoredC, deviceId, 2L, historyA),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(restoredC, deviceId),
        )
        assertEquals(2L, store.maxSequence(restoredC, deviceId))
        assertEquals(0, store.pendingApplicationCount(unrelatedB, deviceId))
    }

    @Test
    fun matchingHistorySelectsUniqueNewestLineageDescendant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val streamA = UUID.randomUUID().toString()
        val descendantD = UUID.randomUUID().toString()
        val unrelatedB = UUID.randomUUID().toString()
        val restoredC = UUID.randomUUID().toString()
        val deviceId = "lineage-source-${UUID.randomUUID()}"
        val history = listOf(command("common-$deviceId", deviceId, 1L))
        seedStream(database, streamA, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, streamA))
        adopt(store, descendantD, deviceId, history)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(
                unrelatedB,
                deviceId,
                1L,
                command("unrelated-$deviceId", deviceId, 1L),
            ),
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(restoredC, deviceId, 1L, history.first()),
        )
        assertEquals(descendantD, store.streamAdoptionProgress(restoredC, deviceId)?.sourceStreamId)
    }

    @Test
    fun unrelatedMatchingHistoryLeavesFailClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val leafA = UUID.randomUUID().toString()
        val leafB = UUID.randomUUID().toString()
        val active = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "ambiguous-history-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, leafA, deviceId, listOf(common))
        seedStream(database, leafB, deviceId, listOf(common))
        seedStream(database, active, deviceId, listOf(command("active-$deviceId", deviceId, 1L)))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, active))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, common),
        )
        assertEquals(active, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun activeMatchingLeafCannotResolveUnrelatedHistoryAmbiguity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val historical = UUID.randomUUID().toString()
        val active = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "active-source-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, historical, deviceId, listOf(common))
        seedStream(database, active, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, active))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, common),
        )
        assertEquals(active, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
        assertEquals(null, store.streamAdoptionProgress(target, deviceId))
    }

    @Test
    fun activeAncestorDoesNotOverrideItsUniqueMatchingDescendant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val ancestorA = UUID.randomUUID().toString()
        val descendantD = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "ancestor-active-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, ancestorA, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, ancestorA))
        adopt(store, descendantD, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, ancestorA))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, common),
        )
        assertEquals(descendantD, store.streamAdoptionProgress(target, deviceId)?.sourceStreamId)
    }

    @Test
    fun highWaterRegressionPermanentlyQuarantinesOnlyTheCompromisedStream() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val compromised = UUID.randomUUID().toString()
        val replacement = UUID.randomUUID().toString()
        val deviceId = "high-water-regression-${UUID.randomUUID()}"
        val history = listOf(command("first-$deviceId", deviceId, 1L), command("second-$deviceId", deviceId, 2L))
        seedStream(database, compromised, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, compromised))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(compromised, deviceId, 1L, history.first()),
        )
        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(compromised, deviceId, 2L, history.first()),
        )
        assertEquals(compromised, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(
                replacement,
                deviceId,
                1L,
                command("replacement-$deviceId", deviceId, 1L),
            ),
        )
        assertEquals(replacement, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
    }

    @Test
    fun initializedStreamRejectsChangedFirstCommandPermanently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val deviceId = "initialized-identity-${UUID.randomUUID()}"
        val original = command("original-$deviceId", deviceId, 1L)
        val changed = command("changed-$deviceId", deviceId, 1L)
        assertTrue(store.receive(stream, original))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, stream))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(stream, deviceId, 1L, changed),
        )
        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(stream, deviceId, 2L, original),
        )
        assertEquals(1L, store.maxSequence(stream, deviceId))
    }

    @Test
    fun unrelatedStreamCursorZeroCanResumeDiscoveryAfterCrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "unrelated-resume-${UUID.randomUUID()}"
        val sourceFirst = command("source-$deviceId", deviceId, 1L)
        val targetFirst = command("target-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(sourceFirst))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, targetFirst),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))

        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, targetFirst),
        )
        assertTrue(store.receive(target, targetFirst))
        assertEquals(1L, store.maxSequence(target, deviceId))
    }

    @Test
    fun emptyStreamThatLaterRestoresHistoryStagesItsVerifiedSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "empty-then-restored-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(first))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 0L, null),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )
        assertEquals(source, store.streamAdoptionProgress(target, deviceId)?.sourceStreamId)
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun emptyStreamThatLaterReusesACommandIdWithDifferentContentIsQuarantined() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "empty-then-conflict-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(first))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 0L, null),
        )
        val changed = first.copy(payloadJson = JSONObject().put("changed", true).toString())

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, changed),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )
    }

    @Test
    fun stagingFirstCommandMutationIsPermanentlyQuarantined() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "staging-identity-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(first))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )
        val changed = first.copy(payloadJson = JSONObject().put("changed", true).toString())

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, changed),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )
    }

    @Test
    fun stagingPageIdentityConflictCannotReceiveFormallyAndRemainsRejectedAfterRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "staging-page-conflict-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        val changed = first.copy(payloadJson = JSONObject().put("changed", true).toString())
        seedStream(database, source, deviceId, listOf(first))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.stageStreamAdoptionPage(target, deviceId, 1L, listOf(changed)),
        )
        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking { store.receive(target, changed) }
        }
        assertEquals(null, store.find(target, changed.commandId))
        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, changed),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun initializedStreamIdentityVerificationQuarantinesMutationBeforeDeliveryOrApplication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val deviceId = "delivery-identity-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        assertTrue(store.receive(stream, first))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, stream))
        val changed = first.copy(payloadJson = JSONObject().put("changed", true).toString())

        assertFalse(store.verifyInitializedStreamIdentity(stream, deviceId, 1L, changed))
        assertFalse(store.verifyInitializedStreamIdentity(stream, deviceId, 1L, first))
        assertEquals(DeviceCommandState.RECEIVED, store.find(stream, first.commandId)?.state)
        assertEquals(DeliveryState.PENDING, store.find(stream, first.commandId)?.ackDeliveryState)
    }

    @Test
    fun initializedEmptyStreamIdentityVerificationAcceptsItsFirstDiscovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val deviceId = "empty-identity-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.UNRELATED_HISTORY,
            store.prepareStreamAdoption(stream, deviceId, 0L, null),
        )

        assertTrue(store.verifyInitializedStreamIdentity(stream, deviceId, 1L, first))
        assertEquals(0L, store.maxSequence(stream, deviceId))
    }

    @Test
    fun cursorZeroWithOrphanBroadcastCannotBeInitialized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val target = UUID.randomUUID().toString()
        val deviceId = "orphan-broadcast-${UUID.randomUUID()}"
        val first = command("first-$deviceId", deviceId, 1L)
        database.deviceCommandDao().insertCursor(
            DeviceCommandCursorEntity(target, deviceId, 0L, 1L),
        )
        assertTrue(
            broadcasts.receive(
                target,
                TextBroadcast(
                    "orphan-${UUID.randomUUID()}",
                    deviceId,
                    1L,
                    "orphan",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    System.currentTimeMillis(),
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, first),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun stagingHighWaterRegressionRemainsQuarantinedAfterRestartAndRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "staging-regression-${UUID.randomUUID()}"
        val history = listOf(command("first-$deviceId", deviceId, 1L), command("second-$deviceId", deviceId, 2L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 2L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.stageStreamAdoptionPage(target, deviceId, 2L, listOf(history.first())),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1L, history.first()),
        )
        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 2L, history.first()),
        )
        assertEquals(0L, store.maxSequence(target, deviceId))
    }

    @Test
    fun stagingRetainsTheHighestObservedHighWaterAboveTheAdoptedPrefix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "staging-observed-maximum-${UUID.randomUUID()}"
        val history = listOf(command("first-$deviceId", deviceId, 1L), command("second-$deviceId", deviceId, 2L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_500L, history.first()),
        )
        assertEquals(2L, store.streamAdoptionProgress(target, deviceId)?.adoptionThroughSequence)
        assertEquals(1_500L, store.streamAdoptionProgress(target, deviceId)?.observedHighWater)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
        assertEquals(1_600L, store.streamAdoptionProgress(target, deviceId)?.observedHighWater)
        store = DeviceCommandStore(database)
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1_500L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
    }

    @Test
    fun everyStagingPagePersistsItsHighestObservedHighWater() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "staging-page-high-water-${UUID.randomUUID()}"
        val history = listOf(command("first-$deviceId", deviceId, 1L), command("second-$deviceId", deviceId, 2L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_500L, history.first()),
        )
        assertTrue(store.observeStreamHighWater(target, deviceId, 1_600L, 1_500L))
        assertEquals(1_600L, store.streamAdoptionProgress(target, deviceId)?.observedHighWater)
        store = DeviceCommandStore(database)
        assertFalse(store.observeStreamHighWater(target, deviceId, 1_500L, 1_500L))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
    }

    @Test
    fun committedAdoptionRetainsHighWaterAboveCursorAndQuarantinesRegression() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "committed-high-water-${UUID.randomUUID()}"
        val history = (1L..1_001L).map { sequence ->
            command("committed-$sequence-$deviceId", deviceId, sequence)
        }
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
        history.chunked(100).forEachIndexed { index, page ->
            val expected = if (index == 10) {
                DeviceCommandStore.StreamAdoptionPreparationResult.READY
            } else {
                DeviceCommandStore.StreamAdoptionPreparationResult.STAGED
            }
            assertEquals(expected, store.stageStreamAdoptionPage(target, deviceId, 1_001L, page))
        }
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(target, deviceId),
        )
        (1_002L..1_101L).forEach { sequence ->
            assertTrue(store.receive(target, command("new-$sequence-$deviceId", deviceId, sequence)))
        }
        database.deviceCommandDao().findCursor(target, deviceId).let { cursor ->
            assertEquals(1_101L, cursor?.afterSequence)
            assertEquals(1_600L, cursor?.maxObservedHighWater)
        }

        assertFalse(store.observeStreamHighWater(target, deviceId, 1_500L))
        store = DeviceCommandStore(database)
        assertFalse(store.observeStreamHighWater(target, deviceId, 1_600L))
        assertEquals(1_101L, store.maxSequence(target, deviceId))
    }

    @Test
    fun committedStreamPersistsEveryHighWaterIncreaseBeforeRejectingADrop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        var store = DeviceCommandStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "committed-high-water-growth-${UUID.randomUUID()}"
        val history = listOf(command("first-$deviceId", deviceId, 1L))
        seedStream(database, source, deviceId, history)
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(target, deviceId, 1_600L, history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(target, deviceId, 1L, history),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(target, deviceId),
        )
        assertTrue(store.observeStreamHighWater(target, deviceId, 1_700L))
        assertEquals(
            1_700L,
            database.deviceCommandDao().findCursor(target, deviceId)?.maxObservedHighWater,
        )

        store = DeviceCommandStore(database)
        assertFalse(store.observeStreamHighWater(target, deviceId, 1_600L))
        assertFalse(store.observeStreamHighWater(target, deviceId, 1_700L))
    }

    @Test
    fun matchingBroadcastIdentityAcrossStreamsPreservesPlaybackAndReplaysReceipt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val secondTarget = UUID.randomUUID().toString()
        val deviceId = "broadcast-rotation-${UUID.randomUUID()}"
        val broadcastId = "stable-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        assertTrue(store.receive(source, common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val received = TextBroadcast(
            broadcastId,
            deviceId,
            7L,
            "do not replay",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            System.currentTimeMillis(),
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(source, received))
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYED,
                received.receivedAtEpochMillis + 1L,
            ),
        )
        assertTrue(
            broadcasts.markReceiptAttempt(
                source,
                deviceId,
                received.broadcastId,
                received.receivedAtEpochMillis + 3L,
            ),
        )
        assertTrue(
            broadcasts.markReceiptDelivered(
                source,
                deviceId,
                broadcastId,
                received.receivedAtEpochMillis + 2L,
            ),
        )
        adopt(store, target, deviceId, listOf(common))
        adopt(store, secondTarget, deviceId, listOf(common))
        assertFalse(broadcasts.receive(secondTarget, received.copy(serverSequence = 2L)))
        assertEquals(BroadcastPlaybackState.PLAYED, broadcasts.find(secondTarget, broadcastId)?.playbackState)
        assertEquals(DeliveryState.PENDING, broadcasts.find(secondTarget, broadcastId)?.receiptDeliveryState)
        assertEquals(DeliveryState.DELIVERED, broadcasts.find(source, broadcastId)?.receiptDeliveryState)
    }

    @Test
    fun unrelatedStreamDoesNotInheritMatchingBroadcastIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val broadcasts = BroadcastStore(database)
        val unrelated = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-unrelated-${UUID.randomUUID()}"
        val received = TextBroadcast(
            "same-${UUID.randomUUID()}",
            deviceId,
            1L,
            "must play again",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            System.currentTimeMillis(),
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(unrelated, received))
        assertTrue(
            broadcasts.updatePlayback(
                unrelated,
                deviceId,
                received.broadcastId,
                BroadcastPlaybackState.PLAYED,
                received.receivedAtEpochMillis + 1L,
            ),
        )

        assertTrue(broadcasts.receive(target, received))
        assertEquals(
            BroadcastPlaybackState.RECEIVED,
            broadcasts.find(target, received.broadcastId)?.playbackState,
        )
    }

    @Test
    fun verifiedLineageRejectsSameBroadcastIdentityWithDifferentContent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-lineage-conflict-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val original = TextBroadcast(
            "stable-${UUID.randomUUID()}",
            deviceId,
            2L,
            "original",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            System.currentTimeMillis(),
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(source, original))
        adopt(store, target, deviceId, listOf(common))

        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking {
                broadcasts.receive(target, original.copy(serverSequence = 2L, text = "changed"))
            }
        }
        assertEquals(null, broadcasts.find(target, original.broadcastId))
    }

    @Test
    fun verifiedLineageRejectsMixedMatchingAndConflictingBroadcastAncestors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val ancestorA = UUID.randomUUID().toString()
        val descendantD = UUID.randomUUID().toString()
        val targetC = UUID.randomUUID().toString()
        val deviceId = "broadcast-mixed-lineage-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, ancestorA, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, ancestorA))
        val broadcastId = "stable-${UUID.randomUUID()}"
        val old = TextBroadcast(
            broadcastId,
            deviceId,
            2L,
            "old",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            System.currentTimeMillis(),
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(ancestorA, old))
        val newer = old.copy(serverSequence = 2L, text = "new")
        seedStream(database, descendantD, deviceId, listOf(common))
        assertTrue(broadcasts.receive(descendantD, newer))
        database.commandStreamAdoptionDao().insertLineage(
            listOf(CommandStreamLineageEntity(descendantD, ancestorA, deviceId)),
        )
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, descendantD))
        adopt(commands, targetC, deviceId, listOf(common))

        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking { broadcasts.receive(targetC, newer) }
        }
        assertEquals(null, broadcasts.find(targetC, broadcastId))
    }

    @Test
    fun inheritedTerminalBroadcastRebuildsItsReceiptTimelineFromTheNewReceiptBaseline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-timeline-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val original = TextBroadcast(
            "broadcast-${UUID.randomUUID()}",
            deviceId,
            2L,
            "recovered",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            100L,
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(source, original))
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                original.broadcastId,
                BroadcastPlaybackState.PLAYING,
                101L,
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                original.broadcastId,
                BroadcastPlaybackState.PLAYED,
                102L,
            ),
        )
        adopt(commands, target, deviceId, listOf(common))

        assertFalse(broadcasts.receive(target, original.copy(receivedAtEpochMillis = 1_000L)))
        val inherited = requireNotNull(broadcasts.find(target, original.broadcastId))
        assertEquals(BroadcastPlaybackState.PLAYED, inherited.playbackState)
        assertEquals(1_000L, inherited.receivedAtEpochMillis)
        assertEquals(1_001L, inherited.playingAtEpochMillis)
        assertEquals(1_002L, inherited.playedAtEpochMillis)
        assertEquals(null, inherited.lastError)
        assertEquals(DeliveryState.PENDING, inherited.receiptDeliveryState)
    }

    @Test
    fun inheritedTerminalBroadcastRejectsAnExhaustedReceiptTimeline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-timeline-max-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val original = TextBroadcast(
            "broadcast-${UUID.randomUUID()}",
            deviceId,
            2L,
            "recovered",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            100L,
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(source, original))
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                original.broadcastId,
                BroadcastPlaybackState.PLAYED,
                101L,
            ),
        )
        adopt(commands, target, deviceId, listOf(common))

        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking {
                broadcasts.receive(target, original.copy(receivedAtEpochMillis = Long.MAX_VALUE))
            }
        }
        assertEquals(null, broadcasts.find(target, original.broadcastId))
    }

    @Test
    fun freshBroadcastRejectsAReceivedTimeWithoutCapacityForPlayingAndTerminalEvents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val broadcasts = BroadcastStore(HelmetDatabase.get(context))
        val stream = UUID.randomUUID().toString()
        val deviceId = "fresh-broadcast-max-${UUID.randomUUID()}"

        listOf(Long.MAX_VALUE - 1L, Long.MAX_VALUE).forEachIndexed { index, receivedAt ->
            val message = TextBroadcast(
                "broadcast-$index-${UUID.randomUUID()}",
                deviceId,
                index + 1L,
                "unrepresentable",
                "en-US",
                1,
                null,
                BroadcastPlaybackState.RECEIVED,
                receivedAt,
                null,
                null,
                null,
                DeliveryState.PENDING,
                0,
            )
            assertThrows(DurableIdentityConflictException::class.java) {
                runBlocking { broadcasts.receive(stream, message) }
            }
            assertEquals(null, broadcasts.find(stream, message.broadcastId))
        }
    }

    @Test
    fun inheritedPlayingBroadcastRejectsAReceiptBaselineWithoutTerminalCapacity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "playing-broadcast-max-${UUID.randomUUID()}"
        val common = command("common-$deviceId", deviceId, 1L)
        seedStream(database, source, deviceId, listOf(common))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        val original = TextBroadcast(
            "broadcast-${UUID.randomUUID()}",
            deviceId,
            2L,
            "playing",
            "en-US",
            1,
            null,
            BroadcastPlaybackState.RECEIVED,
            100L,
            null,
            null,
            null,
            DeliveryState.PENDING,
            0,
        )
        assertTrue(broadcasts.receive(source, original))
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                original.broadcastId,
                BroadcastPlaybackState.PLAYING,
                101L,
            ),
        )
        adopt(commands, target, deviceId, listOf(common))

        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking {
                broadcasts.receive(
                    target,
                    original.copy(receivedAtEpochMillis = Long.MAX_VALUE - 1L),
                )
            }
        }
        assertEquals(null, broadcasts.find(target, original.broadcastId))
    }

    @Test
    fun executedBroadcastWithNonTerminalDurableRowCannotBeAdopted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-non-terminal-${UUID.randomUUID()}"
        val broadcastId = "broadcast-${UUID.randomUUID()}"
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "stuck")
            .put("language", "en-US")
            .put("priority", 1)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val failed = command("command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
            state = DeviceCommandState.FAILED,
            appliedAtEpochMillis = 103L,
            lastError = "TTS_CRASH",
        )
        seedStream(database, source, deviceId, listOf(failed))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "stuck",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    100L,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYING,
                101L,
            ),
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            commands.prepareStreamAdoption(target, deviceId, 1L, failed),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            commands.stageStreamAdoptionPage(target, deviceId, 1L, listOf(failed)),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            commands.commitStreamAdoption(target, deviceId),
        )
        assertEquals(null, commands.find(target, failed.commandId))
    }

    @Test
    fun authoritativeAcknowledgementCannotAdoptANonTerminalBroadcast() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val commands = DeviceCommandStore(database)
        val broadcasts = BroadcastStore(database)
        val source = UUID.randomUUID().toString()
        val target = UUID.randomUUID().toString()
        val deviceId = "broadcast-authoritative-ack-${UUID.randomUUID()}"
        val broadcastId = "broadcast-${UUID.randomUUID()}"
        val payload = JSONObject()
            .put("broadcastId", broadcastId)
            .put("text", "stuck")
            .put("language", "en-US")
            .put("priority", 1)
            .put("expiresAtEpochMillis", JSONObject.NULL)
            .toString()
        val localReceived = command("command-$deviceId", deviceId, 1L).copy(
            type = DeviceCommandType.TEXT_BROADCAST,
            payloadJson = payload,
        )
        assertTrue(commands.receive(source, localReceived))
        database.activeCommandStreamDao().activate(ActiveCommandStreamEntity(deviceId, source))
        assertTrue(
            broadcasts.receive(
                source,
                TextBroadcast(
                    broadcastId,
                    deviceId,
                    1L,
                    "stuck",
                    "en-US",
                    1,
                    null,
                    BroadcastPlaybackState.RECEIVED,
                    100L,
                    null,
                    null,
                    null,
                    DeliveryState.PENDING,
                    0,
                ),
            ),
        )
        assertTrue(
            broadcasts.updatePlayback(
                source,
                deviceId,
                broadcastId,
                BroadcastPlaybackState.PLAYING,
                101L,
            ),
        )
        val authoritative = localReceived.copy(
            state = DeviceCommandState.ACKNOWLEDGED,
            ackDeliveryState = DeliveryState.DELIVERED,
        )

        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            commands.prepareStreamAdoption(target, deviceId, 1L, authoritative),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            commands.stageStreamAdoptionPage(target, deviceId, 1L, listOf(authoritative)),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY,
            commands.commitStreamAdoption(target, deviceId),
        )
        assertEquals(null, commands.find(target, authoritative.commandId))
        assertEquals(source, database.activeCommandStreamDao().find(deviceId)?.commandStreamId)
    }

    @Test
    fun maximumSequenceIsAcceptedOnceAndThenRemainsTerminal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HelmetDatabase.get(context)
        val store = DeviceCommandStore(database)
        val stream = UUID.randomUUID().toString()
        val deviceId = "maximum-sequence-${UUID.randomUUID()}"
        database.deviceCommandDao().insertCursor(
            DeviceCommandCursorEntity(stream, deviceId, Long.MAX_VALUE - 1L),
        )
        val terminal = command("terminal-${UUID.randomUUID()}", deviceId, Long.MAX_VALUE)

        assertTrue(store.receive(stream, terminal))
        assertFalse(store.receive(stream, terminal))
        assertEquals(Long.MAX_VALUE, store.maxSequence(stream, deviceId))
    }

    @Test
    fun replacedBackendAckFailureCanBeDeliveredWhenTheOriginalStreamReturns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeviceCommandStore(HelmetDatabase.get(context))
        val streamA = UUID.randomUUID().toString()
        val streamB = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val deviceId = "ack-return-$nonce"
        val original = command("ack-original-$nonce", deviceId, 1)
        val replacement = command("ack-replacement-$nonce", deviceId, 1)
        assertTrue(store.receive(streamA, original))
        assertTrue(store.markApplied(streamA, original.commandId, original.receivedAtEpochMillis + 1L))
        assertTrue(store.markAckAttempt(streamA, original.commandId, original.receivedAtEpochMillis + 2L))
        assertTrue(store.markAckFailed(streamA, original.commandId, "COMMAND_STREAM_REPLACED"))
        assertTrue(store.receive(streamB, replacement))

        assertEquals(listOf(original.commandId), store.pendingAcks(streamA, deviceId).map { it.commandId })
        assertEquals(0, store.pendingAckCount(streamB, deviceId))
        assertTrue(store.markAckAttempt(streamA, original.commandId, original.receivedAtEpochMillis + 3L))
        assertTrue(store.markAckDelivered(streamA, original.commandId, original.receivedAtEpochMillis + 4L))
        assertEquals(DeliveryState.DELIVERED, store.find(streamA, original.commandId)?.ackDeliveryState)
        assertEquals(0, store.pendingAckCount(streamA, deviceId))
    }

    private fun command(id: String, deviceId: String, sequence: Long) = DeviceCommand(
        commandId = id,
        deviceId = deviceId,
        serverSequence = sequence,
        type = DeviceCommandType.CALL_STATE,
        payloadJson = "{}",
        createdAtEpochMillis = System.currentTimeMillis(),
        state = DeviceCommandState.RECEIVED,
        receivedAtEpochMillis = System.currentTimeMillis(),
        appliedAtEpochMillis = null,
        lastError = null,
        ackDeliveryState = DeliveryState.PENDING,
        ackAttemptCount = 0,
    )

    private suspend fun seedStream(
        database: HelmetDatabase,
        streamId: String,
        deviceId: String,
        history: List<DeviceCommand>,
    ) {
        database.deviceCommandDao().insertAll(history.map { command ->
            DeviceCommandEntity(
                command.commandId,
                streamId,
                command.deviceId,
                command.serverSequence,
                command.type.name,
                command.payloadJson,
                command.createdAtEpochMillis,
                command.state.name,
                command.receivedAtEpochMillis,
                command.appliedAtEpochMillis,
                command.lastError,
                command.ackDeliveryState.name,
                command.ackAttemptCount,
                null,
                null,
            )
        })
        database.deviceCommandDao().insertCursor(
            DeviceCommandCursorEntity(streamId, deviceId, history.last().serverSequence),
        )
    }

    private suspend fun adopt(
        store: DeviceCommandStore,
        targetStreamId: String,
        deviceId: String,
        history: List<DeviceCommand>,
    ) {
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.STAGED,
            store.prepareStreamAdoption(targetStreamId, deviceId, history.size.toLong(), history.first()),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.stageStreamAdoptionPage(
                targetStreamId,
                deviceId,
                history.size.toLong(),
                history,
            ),
        )
        assertEquals(
            DeviceCommandStore.StreamAdoptionPreparationResult.READY,
            store.commitStreamAdoption(targetStreamId, deviceId),
        )
    }
}
