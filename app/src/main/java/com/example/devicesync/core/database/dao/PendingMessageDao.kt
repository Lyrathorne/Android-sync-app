package com.example.devicesync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.devicesync.core.database.entity.PendingMessageEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PendingMessageDao {
    @Query("SELECT * FROM pending_messages WHERE status != 'ACKNOWLEDGED' ORDER BY created_at")
    fun observePendingMessages(): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_messages WHERE recipient_device_id = :deviceId AND status != 'FAILED' AND status != 'ACKNOWLEDGED' ORDER BY created_at")
    suspend fun pendingForDevice(deviceId: String): List<PendingMessageEntity>

    @Query("SELECT * FROM pending_messages WHERE message_id = :messageId")
    suspend fun getMessage(messageId: String): PendingMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMessage(message: PendingMessageEntity)

    @Query("UPDATE pending_messages SET status = :status, attempt_count = attempt_count + 1, last_attempt_at = :attemptAt WHERE message_id = :messageId")
    suspend fun markSent(messageId: String, status: String, attemptAt: Instant)

    @Query("UPDATE pending_messages SET status = :status WHERE message_id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)
}
