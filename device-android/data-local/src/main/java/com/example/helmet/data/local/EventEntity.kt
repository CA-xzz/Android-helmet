package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetEvent

@Entity(tableName = "helmet_events")
data class EventEntity(
    @PrimaryKey val messageId: String,
    val eventType: String,
    val severity: String,
    val payloadJson: String,
    val occurredAtEpochMillis: Long,
) {
    fun toModel(): HelmetEvent = HelmetEvent(
        messageId = messageId,
        eventType = eventType,
        severity = EventSeverity.valueOf(severity),
        payloadJson = payloadJson,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )
}
