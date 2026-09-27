package com.notrace.messenger.crypto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** 1:1 conversation only - excludes group messages, which repurpose contactRandomId to mean "sender within the group" (Phase 10). */
    @Query("SELECT * FROM messages WHERE contactRandomId = :contactRandomId AND groupId IS NULL ORDER BY sentOrReceivedAtEpochMillis ASC")
    fun observeConversation(contactRandomId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY sentOrReceivedAtEpochMillis ASC")
    fun observeGroupConversation(groupId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Query("UPDATE messages SET deliveryState = :state WHERE id = :id")
    suspend fun updateDeliveryState(id: Long, state: DeliveryState)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: String)

    /** Phase 11: every message whose timer has elapsed, regardless of conversation - the expiry sweep's input. */
    @Query("SELECT * FROM messages WHERE expiresAtEpochMillis IS NOT NULL AND expiresAtEpochMillis <= :nowEpochMillis")
    suspend fun getExpired(nowEpochMillis: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    /** Phase 11 "conversation destruction" - full local message history for a 1:1 contact. */
    @Query("DELETE FROM messages WHERE contactRandomId = :contactRandomId AND groupId IS NULL")
    suspend fun deleteAllForContact(contactRandomId: String)

    /** Attachment ids referenced by a contact's 1:1 messages, needed to cascade-delete attachment files before the rows go. */
    @Query("SELECT attachmentId FROM messages WHERE contactRandomId = :contactRandomId AND groupId IS NULL AND attachmentId IS NOT NULL")
    suspend fun getAttachmentIdsForContact(contactRandomId: String): List<String>

    @Query("SELECT attachmentId FROM messages WHERE groupId = :groupId AND attachmentId IS NOT NULL")
    suspend fun getAttachmentIdsForGroup(groupId: String): List<String>
}
