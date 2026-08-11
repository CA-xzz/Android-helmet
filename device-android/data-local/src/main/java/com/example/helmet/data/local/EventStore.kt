package com.example.helmet.data.local

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class EventStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun record(
        eventType: String,
        severity: EventSeverity,
        payloadJson: String,
        messageId: String = idFactory(),
        occurredAtEpochMillis: Long = wallClock(),
    ): Boolean {
        val rowId = database.eventDao().insert(
            EventEntity(
                messageId = messageId,
                eventType = eventType,
                severity = severity.name,
                payloadJson = payloadJson,
                occurredAtEpochMillis = occurredAtEpochMillis,
            ),
        )
        return rowId != -1L
    }

    fun observeRecent(limit: Int = 50): Flow<List<HelmetEvent>> =
        database.eventDao().observeRecent(limit).map { rows -> rows.map(EventEntity::toModel) }

    suspend fun totalCount(): Int = database.eventDao().totalCount()
}
