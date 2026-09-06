package com.example.helmet.data.local

import androidx.room.withTransaction
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class DeviceCommandStore(private val database: HelmetDatabase) {
    enum class StreamAdoptionPreparationResult {
        STAGED,
        READY,
        ALREADY_INITIALIZED,
        UNRELATED_HISTORY,
        INCOMPATIBLE_HISTORY,
    }

    data class StreamAdoptionProgress(
        val sourceStreamId: String,
        val nextSequence: Long,
        val observedHighWater: Long,
        val adoptionThroughSequence: Long,
    )

    suspend fun receive(commandStreamId: String, command: DeviceCommand): Boolean =
        database.withTransaction {
            requireValidCommandStreamId(commandStreamId)
            require(command.state == DeviceCommandState.RECEIVED || command.state == DeviceCommandState.ACKNOWLEDGED)
            if (command.state == DeviceCommandState.ACKNOWLEDGED) {
                require(command.ackDeliveryState == DeliveryState.DELIVERED)
            }
            require(command.serverSequence > 0) { "command sequence must be positive" }
            val dao = database.deviceCommandDao()
            if (database.quarantinedCommandStreamDao().find(commandStreamId, command.deviceId) != null) {
                throw DurableIdentityConflictException("command stream is permanently quarantined")
            }
            val cursor = dao.findCursor(commandStreamId, command.deviceId)
            val afterSequence = cursor?.afterSequence ?: 0L
            require(
                command.serverSequence <= afterSequence ||
                    (afterSequence < Long.MAX_VALUE && command.serverSequence == afterSequence + 1L),
            ) {
                "command sequence contains a gap"
            }
            if (command.serverSequence <= afterSequence) {
                val existing = dao.find(commandStreamId, command.commandId)
                    ?: throw DurableIdentityConflictException(
                        "command sequence conflicts with the stored cursor",
                    )
                requireSameCommandContent(existing, commandStreamId, command)
                return@withTransaction false
            }

            val entity = command.toEntity(commandStreamId)
            val inserted = dao.insert(entity) != -1L
            if (!inserted) {
                val existing = dao.find(commandStreamId, command.commandId)
                    ?: throw DurableIdentityConflictException(
                        "command sequence conflicts with stored content",
                    )
                requireSameCommandContent(existing, commandStreamId, command)
            }
            if (cursor == null) {
                dao.insertCursor(
                    DeviceCommandCursorEntity(
                        commandStreamId,
                        command.deviceId,
                        command.serverSequence,
                        command.serverSequence,
                    ),
                )
            } else {
                check(
                    dao.advanceCursor(
                        commandStreamId,
                        command.deviceId,
                        afterSequence,
                        command.serverSequence,
                    ) == 1,
                ) { "command cursor changed during receive" }
            }
            inserted
        }

    suspend fun find(commandStreamId: String, id: String): DeviceCommand? {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().find(commandStreamId, id)?.toModel()
    }

    suspend fun prepareStreamAdoption(
        targetStreamId: String,
        deviceId: String,
        serverHighWater: Long,
        firstCommand: DeviceCommand?,
    ): StreamAdoptionPreparationResult = database.withTransaction {
        requireValidCommandStreamId(targetStreamId)
        require(serverHighWater >= 0)
        val commands = database.deviceCommandDao()
        val broadcasts = database.textBroadcastDao()
        val adoptions = database.commandStreamAdoptionDao()
        val activeStreams = database.activeCommandStreamDao()
        val quarantinedStreams = database.quarantinedCommandStreamDao()
        if (quarantinedStreams.find(targetStreamId, deviceId) != null) {
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        val existing = adoptions.findAdoption(targetStreamId, deviceId)
        if (existing?.state == ADOPTION_STATE_INCOMPATIBLE) {
            quarantineStream(
                targetStreamId,
                deviceId,
                existing.observedHighWater,
                existing.observedHighWater,
                COMMAND_STREAM_IDENTITY_CONFLICT,
            )
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        val targetCursor = commands.findCursor(targetStreamId, deviceId)
        val targetCommandCount = commands.commandCountForStream(targetStreamId, deviceId)
        val targetBroadcastCount = broadcasts.countForStream(targetStreamId, deviceId)
        val emptyTargetGainedHistory = targetCursor?.afterSequence == 0L &&
            targetCommandCount == 0L &&
            targetBroadcastCount == 0L &&
            serverHighWater > 0L
        if (
            (targetCursor != null || targetCommandCount != 0L || targetBroadcastCount != 0L) &&
            !emptyTargetGainedHistory
        ) {
            if (targetCursor == null ||
                targetCommandCount != targetCursor.afterSequence ||
                (targetCursor.afterSequence == 0L && targetBroadcastCount != 0L)
            ) {
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            if (serverHighWater < targetCursor.maxObservedHighWater) {
                quarantinedStreams.quarantine(
                    QuarantinedCommandStreamEntity(
                        targetStreamId,
                        deviceId,
                        targetCursor.maxObservedHighWater,
                        serverHighWater,
                        COMMAND_HIGH_WATER_REGRESSION,
                    ),
                )
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            val identityIsCompatible = when {
                serverHighWater == 0L -> firstCommand == null && targetCursor.afterSequence == 0L
                firstCommand == null ||
                    firstCommand.serverSequence != 1L ||
                    firstCommand.deviceId != deviceId -> false
                targetCursor.afterSequence == 0L -> true
                else -> commands.findBySequence(targetStreamId, deviceId, 1L)?.let { targetFirst ->
                    sameImmutableCommand(targetFirst, firstCommand)
                } == true
            }
            if (!identityIsCompatible) {
                quarantinedStreams.quarantine(
                    QuarantinedCommandStreamEntity(
                        targetStreamId,
                        deviceId,
                        targetCursor.maxObservedHighWater,
                        serverHighWater,
                        COMMAND_STREAM_IDENTITY_CONFLICT,
                    ),
                )
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            commands.raiseCursorHighWater(targetStreamId, deviceId, serverHighWater)
            activeStreams.activate(ActiveCommandStreamEntity(deviceId, targetStreamId))
            return@withTransaction StreamAdoptionPreparationResult.ALREADY_INITIALIZED
        }
        if (emptyTargetGainedHistory) {
            check(targetCursor != null)
            if (serverHighWater < targetCursor.maxObservedHighWater) {
                quarantineStream(
                    targetStreamId,
                    deviceId,
                    targetCursor.maxObservedHighWater,
                    serverHighWater,
                    COMMAND_HIGH_WATER_REGRESSION,
                )
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            if (
                firstCommand == null ||
                firstCommand.serverSequence != 1L ||
                firstCommand.deviceId != deviceId
            ) {
                quarantineStream(
                    targetStreamId,
                    deviceId,
                    targetCursor.maxObservedHighWater,
                    serverHighWater,
                    COMMAND_STREAM_IDENTITY_CONFLICT,
                )
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
        }
        val activeStreamId = activeStreams.find(deviceId)?.commandStreamId
        if (existing != null) {
            if (serverHighWater < existing.observedHighWater) {
                quarantinedStreams.quarantine(
                    QuarantinedCommandStreamEntity(
                        targetStreamId,
                        deviceId,
                        existing.observedHighWater,
                        serverHighWater,
                        COMMAND_HIGH_WATER_REGRESSION,
                    ),
                )
                adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_INCOMPATIBLE)
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            val identityIsCompatible = firstCommand != null &&
                firstCommand.serverSequence == 1L &&
                firstCommand.deviceId == deviceId &&
                immutableDigest(firstCommand) == existing.sourceFingerprint
            if (!identityIsCompatible) {
                quarantineStream(
                    targetStreamId,
                    deviceId,
                    maxOf(existing.observedHighWater, serverHighWater),
                    serverHighWater,
                    COMMAND_STREAM_IDENTITY_CONFLICT,
                )
                adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_INCOMPATIBLE)
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            adoptions.raiseObservedHighWater(targetStreamId, deviceId, serverHighWater)
            return@withTransaction if (
                (activeStreamId == null || existing.sourceStreamId == activeStreamId) &&
                existing.state != ADOPTION_STATE_INCOMPATIBLE
            ) {
                if (existing.state == ADOPTION_STATE_READY) {
                    StreamAdoptionPreparationResult.READY
                } else {
                    StreamAdoptionPreparationResult.STAGED
                }
            } else {
                StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
        }
        if (serverHighWater == 0L) {
            if (firstCommand != null) {
                quarantineStream(
                    targetStreamId,
                    deviceId,
                    0L,
                    serverHighWater,
                    COMMAND_STREAM_IDENTITY_CONFLICT,
                )
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            if (targetCursor == null) {
                commands.insertCursor(
                    DeviceCommandCursorEntity(targetStreamId, deviceId, 0L, serverHighWater),
                )
            }
            activeStreams.activate(ActiveCommandStreamEntity(deviceId, targetStreamId))
            return@withTransaction StreamAdoptionPreparationResult.UNRELATED_HISTORY
        }
        if (
            firstCommand == null ||
            firstCommand.serverSequence != 1L ||
            firstCommand.deviceId != deviceId
        ) {
            quarantineStream(
                targetStreamId,
                deviceId,
                targetCursor?.maxObservedHighWater ?: 0L,
                serverHighWater,
                COMMAND_STREAM_IDENTITY_CONFLICT,
            )
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        val sourceSelection = selectHistoricalSource(
            commands,
            adoptions,
            targetStreamId,
            deviceId,
            firstCommand,
        )
        if (sourceSelection is HistoricalSourceSelection.Incompatible) {
            quarantineStream(
                targetStreamId,
                deviceId,
                targetCursor?.maxObservedHighWater ?: 0L,
                serverHighWater,
                COMMAND_STREAM_IDENTITY_CONFLICT,
            )
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        if (sourceSelection is HistoricalSourceSelection.Unrelated) {
            if (targetCursor == null) {
                commands.insertCursor(
                    DeviceCommandCursorEntity(targetStreamId, deviceId, 0L, serverHighWater),
                )
            } else {
                commands.raiseCursorHighWater(targetStreamId, deviceId, serverHighWater)
            }
            activeStreams.activate(ActiveCommandStreamEntity(deviceId, targetStreamId))
            return@withTransaction StreamAdoptionPreparationResult.UNRELATED_HISTORY
        }
        val sourceStreamId = (sourceSelection as HistoricalSourceSelection.Selected).streamId
        if (emptyTargetGainedHistory) {
            check(commands.deleteCursor(targetStreamId, deviceId) == 1) {
                "empty target cursor changed during command stream adoption"
            }
        }
        if (activeStreamId != sourceStreamId) {
            activeStreams.activate(ActiveCommandStreamEntity(deviceId, sourceStreamId))
        }
        val sourceCursor = commands.findCursor(sourceStreamId, deviceId)?.afterSequence
            ?: 0L
        val sourceRowCount = commands.commandCountForStream(sourceStreamId, deviceId)
        if (sourceRowCount != sourceCursor) {
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        val sourceFirst = commands.findBySequence(sourceStreamId, deviceId, 1L)
        if (sourceFirst == null || !sameImmutableCommand(sourceFirst, firstCommand)) {
            if (sourceFirst?.commandId == firstCommand.commandId) {
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            commands.insertCursor(
                DeviceCommandCursorEntity(targetStreamId, deviceId, 0L, serverHighWater),
            )
            activeStreams.activate(ActiveCommandStreamEntity(deviceId, targetStreamId))
            return@withTransaction StreamAdoptionPreparationResult.UNRELATED_HISTORY
        }
        val adoptionThroughSequence = minOf(serverHighWater, sourceCursor)
        adoptions.insertAdoption(
            CommandStreamAdoptionEntity(
                targetStreamId = targetStreamId,
                deviceId = deviceId,
                sourceStreamId = sourceStreamId,
                sourceCursor = sourceCursor,
                observedHighWater = serverHighWater,
                adoptionThroughSequence = adoptionThroughSequence,
                nextSequence = 1L,
                state = ADOPTION_STATE_STAGING,
                sourceRowCount = sourceRowCount,
                sourceFingerprint = immutableDigest(sourceFirst),
            ),
        )
        StreamAdoptionPreparationResult.STAGED
    }

    suspend fun verifyInitializedStreamIdentity(
        commandStreamId: String,
        deviceId: String,
        serverHighWater: Long,
        firstCommand: DeviceCommand?,
    ): Boolean = database.withTransaction {
        requireValidCommandStreamId(commandStreamId)
        require(serverHighWater >= 0L)
        val quarantinedStreams = database.quarantinedCommandStreamDao()
        if (quarantinedStreams.find(commandStreamId, deviceId) != null) {
            return@withTransaction false
        }
        val commands = database.deviceCommandDao()
        val cursor = commands.findCursor(commandStreamId, deviceId) ?: return@withTransaction false
        if (database.activeCommandStreamDao().find(deviceId)?.commandStreamId != commandStreamId) {
            return@withTransaction false
        }
        val commandCount = commands.commandCountForStream(commandStreamId, deviceId)
        val broadcastCount = database.textBroadcastDao().countForStream(commandStreamId, deviceId)
        if (
            commandCount != cursor.afterSequence ||
            (cursor.afterSequence == 0L && broadcastCount != 0L)
        ) {
            return@withTransaction false
        }
        if (serverHighWater < cursor.maxObservedHighWater) {
            quarantineStream(
                commandStreamId,
                deviceId,
                cursor.maxObservedHighWater,
                serverHighWater,
                COMMAND_HIGH_WATER_REGRESSION,
            )
            return@withTransaction false
        }
        val identityIsCompatible = initializedStreamIdentityIsCompatible(
            commands,
            commandStreamId,
            deviceId,
            cursor.afterSequence,
            serverHighWater,
            firstCommand,
        )
        if (!identityIsCompatible) {
            quarantineStream(
                commandStreamId,
                deviceId,
                cursor.maxObservedHighWater,
                serverHighWater,
                COMMAND_STREAM_IDENTITY_CONFLICT,
            )
            return@withTransaction false
        }
        commands.raiseCursorHighWater(commandStreamId, deviceId, serverHighWater)
        true
    }

    suspend fun streamAdoptionProgress(
        targetStreamId: String,
        deviceId: String,
    ): StreamAdoptionProgress? = database.commandStreamAdoptionDao()
        .findAdoption(targetStreamId, deviceId)
        ?.let {
            StreamAdoptionProgress(
                it.sourceStreamId,
                it.nextSequence,
                it.observedHighWater,
                it.adoptionThroughSequence,
            )
        }

    suspend fun stageStreamAdoptionPage(
        targetStreamId: String,
        deviceId: String,
        adoptionThroughSequence: Long,
        page: List<DeviceCommand>,
    ): StreamAdoptionPreparationResult = database.withTransaction {
        val adoptions = database.commandStreamAdoptionDao()
        val adoption = adoptions.findAdoption(targetStreamId, deviceId)
            ?: return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        if (
            database.activeCommandStreamDao().find(deviceId)?.commandStreamId !=
                adoption.sourceStreamId ||
            adoption.adoptionThroughSequence != adoptionThroughSequence ||
            adoption.state == ADOPTION_STATE_INCOMPATIBLE ||
            page.isEmpty()
        ) {
            markAdoptionIncompatible(adoption)
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        var expectedSequence = adoption.nextSequence
        val stagedRows = ArrayList<CommandStreamAdoptionRowEntity>(page.size)
        for (authoritative in page) {
            if (
                expectedSequence > adoptionThroughSequence ||
                authoritative.deviceId != deviceId ||
                authoritative.serverSequence != expectedSequence
            ) {
                markAdoptionIncompatible(adoption)
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            val source = database.deviceCommandDao().findBySequence(
                adoption.sourceStreamId,
                deviceId,
                expectedSequence,
            )
            if (
                source == null ||
                (source.commandId == authoritative.commandId &&
                    !sameImmutableCommand(source, authoritative)) ||
                (source.commandId == authoritative.commandId &&
                    source.state == DeviceCommandState.ACKNOWLEDGED.name &&
                    authoritative.state != DeviceCommandState.ACKNOWLEDGED)
            ) {
                markAdoptionIncompatible(adoption)
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            if (source.commandId != authoritative.commandId) {
                val prefix = expectedSequence - 1L
                if (stagedRows.isNotEmpty()) {
                    adoptions.insertRows(stagedRows)
                }
                if (prefix <= 0L ||
                    adoptions.completeAtPrefix(targetStreamId, deviceId, prefix) != 1
                ) {
                    markAdoptionIncompatible(adoption)
                    return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
                }
                return@withTransaction StreamAdoptionPreparationResult.READY
            }
            stagedRows += CommandStreamAdoptionRowEntity(
                targetStreamId,
                deviceId,
                expectedSequence,
                authoritative.commandId,
                immutableDigest(authoritative),
                authoritative.state == DeviceCommandState.ACKNOWLEDGED,
            )
            if (expectedSequence < adoptionThroughSequence) expectedSequence += 1L
        }
        adoptions.insertRows(stagedRows)
        check(
            adoptions.advanceNextSequence(
                targetStreamId,
                deviceId,
                adoption.nextSequence,
                expectedSequence,
            ) == 1,
        ) { "command stream adoption progress changed" }
        if (adoptions.rowCount(targetStreamId, deviceId) == adoptionThroughSequence) {
            adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_READY)
            StreamAdoptionPreparationResult.READY
        } else {
            StreamAdoptionPreparationResult.STAGED
        }
    }

    suspend fun commitStreamAdoption(
        targetStreamId: String,
        deviceId: String,
    ): StreamAdoptionPreparationResult = database.withTransaction {
        val commands = database.deviceCommandDao()
        val broadcasts = database.textBroadcastDao()
        val adoptions = database.commandStreamAdoptionDao()
        val adoption = adoptions.findAdoption(targetStreamId, deviceId)
            ?: return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        if (
            adoption.state != ADOPTION_STATE_READY ||
            database.activeCommandStreamDao().find(deviceId)?.commandStreamId !=
                adoption.sourceStreamId ||
            adoptions.rowCount(targetStreamId, deviceId) != adoption.adoptionThroughSequence ||
            commands.findCursor(targetStreamId, deviceId) != null ||
            commands.commandCountForStream(targetStreamId, deviceId) != 0L ||
            broadcasts.countForStream(targetStreamId, deviceId) != 0L ||
            commands.findCursor(adoption.sourceStreamId, deviceId)?.afterSequence != adoption.sourceCursor ||
            commands.commandCountForStream(adoption.sourceStreamId, deviceId) != adoption.sourceRowCount
        ) {
            adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_INCOMPATIBLE)
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        var verifiedThrough = 0L
        while (verifiedThrough < adoption.adoptionThroughSequence) {
            val stagedPage = adoptions.rowsPage(
                targetStreamId,
                deviceId,
                verifiedThrough,
                ADOPTION_PAGE_SIZE,
            )
            val sourcePage = commands.commandsAfter(
                adoption.sourceStreamId,
                deviceId,
                verifiedThrough,
                ADOPTION_PAGE_SIZE,
            ).takeWhile { it.serverSequence <= adoption.adoptionThroughSequence }
            if (
                stagedPage.isEmpty() ||
                stagedPage.size != sourcePage.size ||
                sourcePage.zip(stagedPage).any { (source, row) ->
                    source.serverSequence != row.sequence ||
                        source.commandId != row.commandId ||
                        immutableDigest(source) != row.immutableDigest
                }
            ) {
                adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_INCOMPATIBLE)
                return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
            }
            verifiedThrough = stagedPage.last().sequence
        }
        if (!broadcastPrefixCompatible(
                adoption.sourceStreamId,
                adoption.targetStreamId,
                deviceId,
                adoption.adoptionThroughSequence,
            )
        ) {
            adoptions.updateState(targetStreamId, deviceId, ADOPTION_STATE_INCOMPATIBLE)
            return@withTransaction StreamAdoptionPreparationResult.INCOMPATIBLE_HISTORY
        }
        var copiedThrough = 0L
        while (copiedThrough < adoption.adoptionThroughSequence) {
            val sourcePage = commands.commandsAfter(
                adoption.sourceStreamId,
                deviceId,
                copiedThrough,
                ADOPTION_PAGE_SIZE,
            ).takeWhile { it.serverSequence <= adoption.adoptionThroughSequence }
            check(sourcePage.isNotEmpty()) { "source command prefix changed during adoption" }
            commands.insertAll(sourcePage.map { it.copy(commandStreamId = targetStreamId) })
            copiedThrough = sourcePage.last().serverSequence
        }
        var copiedBroadcastThrough = 0L
        while (copiedBroadcastThrough < adoption.adoptionThroughSequence) {
            val sourcePage = broadcasts.broadcastsAfter(
                adoption.sourceStreamId,
                deviceId,
                copiedBroadcastThrough,
                ADOPTION_PAGE_SIZE,
            ).takeWhile { it.serverSequence <= adoption.adoptionThroughSequence }
            if (sourcePage.isEmpty()) break
            broadcasts.insertAll(sourcePage.map { it.copy(commandStreamId = targetStreamId) })
            sourcePage.forEach { broadcast ->
                check(
                    broadcasts.resetReceiptForAuthoritativeReplay(
                        targetStreamId,
                        deviceId,
                        broadcast.serverSequence,
                    ) == 1,
                )
            }
            copiedBroadcastThrough = sourcePage.last().serverSequence
        }
        var adjustedThrough = 0L
        while (adjustedThrough < adoption.adoptionThroughSequence) {
            val stagedPage = adoptions.rowsPage(
                targetStreamId,
                deviceId,
                adjustedThrough,
                ADOPTION_PAGE_SIZE,
            )
            stagedPage.forEach { row ->
                if (row.serverAcknowledged) {
                    check(commands.markServerAcknowledged(targetStreamId, deviceId, row.commandId) == 1)
                } else {
                    commands.resetUnacknowledgedDeliveredResult(targetStreamId, deviceId, row.commandId)
                }
            }
            adjustedThrough = stagedPage.last().sequence
        }
        commands.insertCursor(
            DeviceCommandCursorEntity(
                targetStreamId,
                deviceId,
                adoption.adoptionThroughSequence,
                adoption.observedHighWater,
            ),
        )
        database.activeCommandStreamDao().activate(
            ActiveCommandStreamEntity(deviceId, targetStreamId),
        )
        adoptions.insertLineage(
            buildList {
                add(CommandStreamLineageEntity(targetStreamId, adoption.sourceStreamId, deviceId))
                addAll(
                    adoptions.sourceLineage(adoption.sourceStreamId, deviceId).map { ancestor ->
                        CommandStreamLineageEntity(targetStreamId, ancestor, deviceId)
                    },
                )
            },
        )
        adoptions.deleteAdoption(targetStreamId, deviceId)
        StreamAdoptionPreparationResult.READY
    }

    suspend fun maxSequence(commandStreamId: String, deviceId: String): Long {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().findCursor(commandStreamId, deviceId)?.afterSequence ?: 0L
    }

    suspend fun quarantineHighWaterRegression(
        commandStreamId: String,
        deviceId: String,
        observedHighWater: Long,
        expectedMinimum: Long? = null,
    ) = database.withTransaction {
        requireValidCommandStreamId(commandStreamId)
        require(observedHighWater >= 0L)
        val cursorHighWater = database.deviceCommandDao()
            .findCursor(commandStreamId, deviceId)
            ?.maxObservedHighWater
            ?: 0L
        val adoptionMinimum = database.commandStreamAdoptionDao()
            .findAdoption(commandStreamId, deviceId)
            ?.observedHighWater
            ?: 0L
        val requiredMinimum = maxOf(cursorHighWater, adoptionMinimum, expectedMinimum ?: 0L)
        require(observedHighWater < requiredMinimum) { "command high-water did not regress" }
        database.quarantinedCommandStreamDao().quarantine(
            QuarantinedCommandStreamEntity(
                commandStreamId,
                deviceId,
                requiredMinimum,
                observedHighWater,
                COMMAND_HIGH_WATER_REGRESSION,
            ),
        )
    }

    suspend fun observeStreamHighWater(
        commandStreamId: String,
        deviceId: String,
        observedHighWater: Long,
        expectedMinimum: Long = 0L,
    ): Boolean = database.withTransaction {
        requireValidCommandStreamId(commandStreamId)
        require(observedHighWater >= 0L && expectedMinimum >= 0L)
        val quarantined = database.quarantinedCommandStreamDao()
        if (quarantined.find(commandStreamId, deviceId) != null) return@withTransaction false
        val commands = database.deviceCommandDao()
        val cursor = commands.findCursor(commandStreamId, deviceId)
        val adoption = database.commandStreamAdoptionDao().findAdoption(commandStreamId, deviceId)
        val requiredMinimum = maxOf(
            cursor?.maxObservedHighWater ?: 0L,
            adoption?.observedHighWater ?: 0L,
            expectedMinimum,
        )
        if (observedHighWater < requiredMinimum) {
            quarantined.quarantine(
                QuarantinedCommandStreamEntity(
                    commandStreamId,
                    deviceId,
                    requiredMinimum,
                    observedHighWater,
                    COMMAND_HIGH_WATER_REGRESSION,
                ),
            )
            adoption?.let {
                database.commandStreamAdoptionDao().updateState(
                    commandStreamId,
                    deviceId,
                    ADOPTION_STATE_INCOMPATIBLE,
                )
            }
            return@withTransaction false
        }
        if (adoption != null) {
            database.commandStreamAdoptionDao().raiseObservedHighWater(
                commandStreamId,
                deviceId,
                observedHighWater,
            )
        }
        if (cursor != null) {
            commands.raiseCursorHighWater(commandStreamId, deviceId, observedHighWater)
        }
        true
    }
    suspend fun pendingApplication(
        commandStreamId: String,
        deviceId: String,
        limit: Int = 100,
    ): List<DeviceCommand> {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().pendingApplication(commandStreamId, deviceId, limit)
            .map(DeviceCommandEntity::toModel)
    }
    suspend fun pendingApplicationCount(commandStreamId: String, deviceId: String): Int {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().pendingApplicationCount(commandStreamId, deviceId)
    }
    suspend fun pendingAcks(
        commandStreamId: String,
        deviceId: String,
        limit: Int = 100,
    ): List<DeviceCommand> {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().pendingAcks(commandStreamId, deviceId, limit)
            .map(DeviceCommandEntity::toModel)
    }
    suspend fun pendingAckCount(commandStreamId: String, deviceId: String): Int {
        requireValidCommandStreamId(commandStreamId)
        return database.deviceCommandDao().pendingAckCount(commandStreamId, deviceId)
    }
    suspend fun markApplied(commandStreamId: String, id: String, at: Long): Boolean =
        database.deviceCommandDao().markApplied(
            commandStreamId,
            id,
            DeviceCommandState.APPLIED.name,
            at,
            null,
        ) == 1
    suspend fun markFailed(commandStreamId: String, id: String, at: Long, error: String): Boolean =
        database.deviceCommandDao().markApplied(
            commandStreamId,
            id,
            DeviceCommandState.FAILED.name,
            at,
            error.take(MAX_ERROR_LENGTH),
        ) == 1
    suspend fun markAckAttempt(commandStreamId: String, id: String, at: Long): Boolean =
        database.deviceCommandDao().markAckAttempt(commandStreamId, id, at) == 1
    suspend fun markAckDelivered(commandStreamId: String, id: String, at: Long): Boolean =
        database.deviceCommandDao().markAckDelivered(commandStreamId, id, at) == 1
    suspend fun markAckFailed(commandStreamId: String, id: String, error: String): Boolean =
        database.deviceCommandDao().markAckFailed(
            commandStreamId,
            id,
            error.take(MAX_ERROR_LENGTH),
        ) == 1
    suspend fun markAckRejected(commandStreamId: String, id: String, error: String): Boolean =
        database.deviceCommandDao().markAckRejected(
            commandStreamId,
            id,
            error.take(MAX_ERROR_LENGTH),
        ) == 1

    private fun DeviceCommand.toEntity(commandStreamId: String) = DeviceCommandEntity(
        commandId = commandId,
        commandStreamId = commandStreamId,
        deviceId = deviceId,
        serverSequence = serverSequence,
        type = type.name,
        payloadJson = payloadJson,
        createdAtEpochMillis = createdAtEpochMillis,
        state = state.name,
        receivedAtEpochMillis = receivedAtEpochMillis,
        appliedAtEpochMillis = appliedAtEpochMillis,
        lastError = lastError,
        ackDeliveryState = ackDeliveryState.name,
        ackAttemptCount = ackAttemptCount,
        lastAckAttemptAtEpochMillis = null,
        ackDeliveredAtEpochMillis = receivedAtEpochMillis.takeIf {
            ackDeliveryState == DeliveryState.DELIVERED
        },
    )

    private suspend fun broadcastPrefixCompatible(
        sourceStreamId: String,
        targetStreamId: String,
        deviceId: String,
        throughSequence: Long,
    ): Boolean {
        var broadcastAfter = 0L
        var storedBroadcastsAreCompatible = true
        while (storedBroadcastsAreCompatible && broadcastAfter < throughSequence) {
            val page = database.textBroadcastDao().broadcastsAfter(
                sourceStreamId,
                deviceId,
                broadcastAfter,
                ADOPTION_PAGE_SIZE,
            ).takeWhile { it.serverSequence <= throughSequence }
            if (page.isEmpty()) break
            storedBroadcastsAreCompatible = page.all { broadcast ->
                val command = database.deviceCommandDao().findBySequence(
                    sourceStreamId,
                    deviceId,
                    broadcast.serverSequence,
                ) ?: return@all false
                command.type == DeviceCommandType.TEXT_BROADCAST.name &&
                    broadcastMatchesCommand(broadcast, command.payloadJson)
            }
            broadcastAfter = page.last().serverSequence
        }
        var afterSequence = 0L
        var executedBroadcastsAreDurable = true
        while (executedBroadcastsAreDurable && afterSequence < throughSequence) {
            val page = database.deviceCommandDao().commandsAfter(
                sourceStreamId,
                deviceId,
                afterSequence,
                ADOPTION_PAGE_SIZE,
            ).takeWhile { it.serverSequence <= throughSequence }
            if (page.isEmpty()) return false
            executedBroadcastsAreDurable = page.all { source ->
                val authoritativeAcknowledged = database.commandStreamAdoptionDao().findRow(
                    targetStreamId,
                    deviceId,
                    source.serverSequence,
                )?.serverAcknowledged == true
                source.type != DeviceCommandType.TEXT_BROADCAST.name ||
                    (source.state !in setOf(DeviceCommandState.APPLIED.name, DeviceCommandState.FAILED.name) &&
                        !authoritativeAcknowledged) ||
                    database.textBroadcastDao().findBySequence(
                        sourceStreamId,
                        deviceId,
                        source.serverSequence,
                    )?.let { broadcast ->
                        broadcastMatchesCommand(broadcast, source.payloadJson) &&
                            broadcast.playbackState in TERMINAL_BROADCAST_STATES
                    } == true
            }
            afterSequence = page.last().serverSequence
        }
        return storedBroadcastsAreCompatible && executedBroadcastsAreDurable
    }

    private fun broadcastMatchesCommand(broadcast: TextBroadcastEntity, payloadJson: String): Boolean =
        runCatching {
            val payload = JSONObject(payloadJson)
            payload.getString("broadcastId") == broadcast.broadcastId &&
                payload.getString("text") == broadcast.text &&
                payload.getString("language") == broadcast.language &&
                payload.getInt("priority") == broadcast.priority &&
                payload.optionalLong("expiresAtEpochMillis") == broadcast.expiresAtEpochMillis
        }.getOrDefault(false)

    private sealed interface HistoricalSourceSelection {
        data class Selected(val streamId: String) : HistoricalSourceSelection
        data object Unrelated : HistoricalSourceSelection
        data object Incompatible : HistoricalSourceSelection
    }

    private suspend fun selectHistoricalSource(
        commands: DeviceCommandDao,
        adoptions: CommandStreamAdoptionDao,
        targetStreamId: String,
        deviceId: String,
        firstCommand: DeviceCommand,
    ): HistoricalSourceSelection {
        val identityCandidates = commands.streamsWithFirstCommand(deviceId, firstCommand.commandId)
            .filter { it != targetStreamId }
        val exactCandidates = identityCandidates.filter { streamId ->
            commands.findBySequence(streamId, deviceId, 1L)?.let { source ->
                sameImmutableCommand(source, firstCommand)
            } == true
        }
        val ancestorCandidates = exactCandidates.flatMapTo(mutableSetOf()) { candidate ->
            adoptions.sourceLineage(candidate, deviceId).filter { ancestor ->
                ancestor in exactCandidates
            }
        }
        val newestCandidates = exactCandidates.filterNot { it in ancestorCandidates }
        return when {
            newestCandidates.size == 1 -> HistoricalSourceSelection.Selected(newestCandidates.single())
            newestCandidates.size > 1 || identityCandidates.isNotEmpty() ->
                HistoricalSourceSelection.Incompatible
            else -> HistoricalSourceSelection.Unrelated
        }
    }

    private suspend fun initializedStreamIdentityIsCompatible(
        commands: DeviceCommandDao,
        commandStreamId: String,
        deviceId: String,
        localCursor: Long,
        serverHighWater: Long,
        firstCommand: DeviceCommand?,
    ): Boolean = when {
        serverHighWater == 0L -> firstCommand == null && localCursor == 0L
        firstCommand == null ||
            firstCommand.serverSequence != 1L ||
            firstCommand.deviceId != deviceId -> false
        localCursor == 0L -> true
        else -> commands.findBySequence(commandStreamId, deviceId, 1L)?.let { storedFirst ->
            sameImmutableCommand(storedFirst, firstCommand)
        } == true
    }

    private suspend fun quarantineStream(
        commandStreamId: String,
        deviceId: String,
        requiredHighWater: Long,
        observedHighWater: Long,
        reason: String,
    ) {
        database.quarantinedCommandStreamDao().quarantine(
            QuarantinedCommandStreamEntity(
                commandStreamId,
                deviceId,
                requiredHighWater,
                observedHighWater,
                reason,
            ),
        )
    }

    private suspend fun markAdoptionIncompatible(adoption: CommandStreamAdoptionEntity) {
        database.commandStreamAdoptionDao().updateState(
            adoption.targetStreamId,
            adoption.deviceId,
            ADOPTION_STATE_INCOMPATIBLE,
        )
        quarantineStream(
            adoption.targetStreamId,
            adoption.deviceId,
            adoption.observedHighWater,
            adoption.observedHighWater,
            COMMAND_STREAM_IDENTITY_CONFLICT,
        )
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
        private const val ADOPTION_PAGE_SIZE = 100
        const val LEGACY_COMMAND_STREAM_ID = "legacy-v13-unscoped"
        private const val ADOPTION_STATE_STAGING = "STAGING"
        private const val ADOPTION_STATE_READY = "READY"
        private const val ADOPTION_STATE_INCOMPATIBLE = "INCOMPATIBLE"
        private const val COMMAND_HIGH_WATER_REGRESSION = "COMMAND_HIGH_WATER_REGRESSION"
        private const val COMMAND_STREAM_IDENTITY_CONFLICT = "COMMAND_STREAM_IDENTITY_CONFLICT"
        private val TERMINAL_BROADCAST_STATES = setOf(
            "PLAYED",
            "FAILED",
            "EXPIRED",
        )

        fun requireValidCommandStreamId(value: String) {
            val parsed = runCatching { UUID.fromString(value) }.getOrNull()
            require(value == LEGACY_COMMAND_STREAM_ID || parsed?.toString() == value) {
                "invalid command stream ID"
            }
        }

        private fun requireSameCommandContent(
            existing: DeviceCommandEntity,
            commandStreamId: String,
            incoming: DeviceCommand,
        ) {
            if (
                existing.commandStreamId != commandStreamId ||
                existing.deviceId != incoming.deviceId ||
                existing.serverSequence != incoming.serverSequence ||
                existing.type != incoming.type.name ||
                canonicalJson(existing.payloadJson) != canonicalJson(incoming.payloadJson) ||
                existing.createdAtEpochMillis != incoming.createdAtEpochMillis
            ) {
                throw DurableIdentityConflictException("command ID conflicts with stored content")
            }
        }

        private fun sameImmutableCommand(
            existing: DeviceCommandEntity,
            incoming: DeviceCommand,
        ): Boolean = existing.commandId == incoming.commandId &&
            existing.deviceId == incoming.deviceId &&
            existing.serverSequence == incoming.serverSequence &&
            existing.type == incoming.type.name &&
            existing.createdAtEpochMillis == incoming.createdAtEpochMillis &&
            runCatching { canonicalJson(existing.payloadJson) == canonicalJson(incoming.payloadJson) }
                .getOrDefault(false)

        private fun immutableDigest(command: DeviceCommandEntity): String = immutableDigest(
            command.commandId,
            command.deviceId,
            command.serverSequence,
            command.type,
            command.payloadJson,
            command.createdAtEpochMillis,
        )

        private fun immutableDigest(command: DeviceCommand): String = immutableDigest(
            command.commandId,
            command.deviceId,
            command.serverSequence,
            command.type.name,
            command.payloadJson,
            command.createdAtEpochMillis,
        )

        private fun immutableDigest(
            commandId: String,
            deviceId: String,
            sequence: Long,
            type: String,
            payloadJson: String,
            createdAtEpochMillis: Long,
        ): String {
            val values = listOf(
                commandId,
                deviceId,
                sequence.toString(),
                type,
                canonicalJson(payloadJson),
                createdAtEpochMillis.toString(),
            )
            val canonical = buildString {
                values.forEach { value -> append(value.length).append(':').append(value) }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun canonicalJson(raw: String): String = canonicalJsonValue(JSONObject(raw))

        private fun canonicalJsonValue(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
                prefix = "{",
                postfix = "}",
                separator = ",",
            ) { key -> "${JSONObject.quote(key)}:${canonicalJsonValue(value.get(key))}" }
            is JSONArray -> (0 until value.length()).joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) { index -> canonicalJsonValue(value.get(index)) }
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> throw IllegalArgumentException("unsupported JSON value")
        }

        private fun JSONObject.optionalLong(name: String): Long? =
            if (isNull(name)) null else getLong(name)
    }
}
