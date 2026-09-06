package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "active_command_streams")
data class ActiveCommandStreamEntity(
    @PrimaryKey val deviceId: String,
    val commandStreamId: String,
)

@Dao
interface ActiveCommandStreamDao {
    @Query("SELECT * FROM active_command_streams WHERE deviceId = :deviceId LIMIT 1")
    suspend fun find(deviceId: String): ActiveCommandStreamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun activate(entity: ActiveCommandStreamEntity)
}

@Entity(
    tableName = "quarantined_command_streams",
    primaryKeys = ["commandStreamId", "deviceId"],
)
data class QuarantinedCommandStreamEntity(
    val commandStreamId: String,
    val deviceId: String,
    val requiredHighWater: Long,
    val observedHighWater: Long,
    val reason: String,
)

@Dao
interface QuarantinedCommandStreamDao {
    @Query("SELECT * FROM quarantined_command_streams WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId LIMIT 1")
    suspend fun find(commandStreamId: String, deviceId: String): QuarantinedCommandStreamEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun quarantine(entity: QuarantinedCommandStreamEntity): Long
}
