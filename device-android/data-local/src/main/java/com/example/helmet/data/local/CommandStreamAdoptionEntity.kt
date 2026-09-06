package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "command_stream_adoptions",
    primaryKeys = ["targetStreamId", "deviceId"],
)
data class CommandStreamAdoptionEntity(
    val targetStreamId: String,
    val deviceId: String,
    val sourceStreamId: String,
    val sourceCursor: Long,
    val observedHighWater: Long,
    val adoptionThroughSequence: Long,
    val nextSequence: Long,
    val state: String,
    val sourceRowCount: Long,
    val sourceFingerprint: String,
)

@Entity(
    tableName = "command_stream_adoption_rows",
    primaryKeys = ["targetStreamId", "deviceId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = CommandStreamAdoptionEntity::class,
            parentColumns = ["targetStreamId", "deviceId"],
            childColumns = ["targetStreamId", "deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["targetStreamId", "deviceId", "commandId"], unique = true),
    ],
)
data class CommandStreamAdoptionRowEntity(
    val targetStreamId: String,
    val deviceId: String,
    val sequence: Long,
    val commandId: String,
    val immutableDigest: String,
    val serverAcknowledged: Boolean,
)

@Entity(
    tableName = "command_stream_lineage",
    primaryKeys = ["targetStreamId", "sourceStreamId", "deviceId"],
    indices = [Index(value = ["targetStreamId", "deviceId"])],
)
data class CommandStreamLineageEntity(
    val targetStreamId: String,
    val sourceStreamId: String,
    val deviceId: String,
)

@Dao
interface CommandStreamAdoptionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdoption(entity: CommandStreamAdoptionEntity)

    @Query(
        "SELECT * FROM command_stream_adoptions " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId LIMIT 1",
    )
    suspend fun findAdoption(
        targetStreamId: String,
        deviceId: String,
    ): CommandStreamAdoptionEntity?

    @Query(
        "UPDATE command_stream_adoptions SET nextSequence = :nextSequence " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId " +
            "AND nextSequence = :expectedNextSequence",
    )
    suspend fun advanceNextSequence(
        targetStreamId: String,
        deviceId: String,
        expectedNextSequence: Long,
        nextSequence: Long,
    ): Int

    @Query(
        "UPDATE command_stream_adoptions SET state = :state " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId",
    )
    suspend fun updateState(
        targetStreamId: String,
        deviceId: String,
        state: String,
    ): Int

    @Query(
        "UPDATE command_stream_adoptions SET adoptionThroughSequence = :adoptionThroughSequence, " +
            "nextSequence = :adoptionThroughSequence, state = 'READY' " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId " +
            "AND state = 'STAGING' AND adoptionThroughSequence >= :adoptionThroughSequence",
    )
    suspend fun completeAtPrefix(
        targetStreamId: String,
        deviceId: String,
        adoptionThroughSequence: Long,
    ): Int

    @Query(
        "UPDATE command_stream_adoptions SET observedHighWater = :observedHighWater " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId " +
            "AND observedHighWater < :observedHighWater AND state != 'INCOMPATIBLE'",
    )
    suspend fun raiseObservedHighWater(
        targetStreamId: String,
        deviceId: String,
        observedHighWater: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRows(rows: List<CommandStreamAdoptionRowEntity>)

    @Query(
        "SELECT * FROM command_stream_adoption_rows " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId " +
            "AND sequence > :afterSequence ORDER BY sequence LIMIT :limit",
    )
    suspend fun rowsPage(
        targetStreamId: String,
        deviceId: String,
        afterSequence: Long,
        limit: Int,
    ): List<CommandStreamAdoptionRowEntity>

    @Query(
        "SELECT * FROM command_stream_adoption_rows " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId " +
            "AND sequence = :sequence LIMIT 1",
    )
    suspend fun findRow(
        targetStreamId: String,
        deviceId: String,
        sequence: Long,
    ): CommandStreamAdoptionRowEntity?

    @Query(
        "SELECT COUNT(*) FROM command_stream_adoption_rows " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId",
    )
    suspend fun rowCount(targetStreamId: String, deviceId: String): Long

    @Query(
        "DELETE FROM command_stream_adoption_rows " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId",
    )
    suspend fun deleteRows(targetStreamId: String, deviceId: String): Int

    @Query(
        "DELETE FROM command_stream_adoptions " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId",
    )
    suspend fun deleteAdoption(targetStreamId: String, deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLineage(rows: List<CommandStreamLineageEntity>): List<Long>

    @Query(
        "SELECT sourceStreamId FROM command_stream_lineage " +
            "WHERE targetStreamId = :targetStreamId AND deviceId = :deviceId",
    )
    suspend fun sourceLineage(targetStreamId: String, deviceId: String): List<String>
}
