package com.example.devicesync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.devicesync.core.database.entity.ProcessedMessageEntity
import java.time.Instant

@Dao
interface ProcessedMessageDao {
    @Query("SELECT COUNT(*) FROM processed_messages WHERE sender_device_id = :senderDeviceId AND message_id = :messageId")
    suspend fun countProcessed(senderDeviceId: String, messageId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markProcessed(message: ProcessedMessageEntity)

    @Query("DELETE FROM processed_messages WHERE processed_at < :timestamp")
    suspend fun deleteOlderThan(timestamp: Instant)
}
