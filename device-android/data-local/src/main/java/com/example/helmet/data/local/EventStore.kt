package com.example.helmet.data.local

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetEvent
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class EventStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val maxRetainedEvents: Int = DEFAULT_MAX_RETAINED_EVENTS,
) {
    init {
        require(maxRetainedEvents > 0)
    }

    suspend fun record(
        eventType: String,
        severity: EventSeverity,
        payloadJson: String,
        messageId: String = idFactory(),
        occurredAtEpochMillis: Long = wallClock(),
    ): Boolean {
        return database.withTransaction {
            val rowId = database.eventDao().insert(
                EventEntity(
                    messageId = messageId,
                    eventType = eventType,
                    severity = severity.name,
                    payloadJson = payloadJson,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                ),
            )
            if (rowId == -1L) {
                val existing = requireNotNull(database.eventDao().find(messageId))
                if (
                    existing.eventType != eventType ||
                    existing.severity != severity.name ||
                    existing.payloadJson != payloadJson
                ) {
                    throw DurableIdentityConflictException(
                        "event message ID conflicts with stored content",
                    )
                }
            }
            if (rowId != -1L) database.eventDao().pruneOldest(maxRetainedEvents)
            rowId != -1L
        }
    }

    fun observeRecent(limit: Int = 50): Flow<List<HelmetEvent>> =
        database.eventDao().observeRecent(limit).map { rows -> rows.map(EventEntity::toModel) }

    suspend fun totalCount(): Int = database.eventDao().totalCount()

    companion object {
        const val DEFAULT_MAX_RETAINED_EVENTS = 10_000
    }
}
