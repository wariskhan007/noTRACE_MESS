package com.notrace.messenger.attachment.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE id = :id")
    fun observe(id: String): Flow<AttachmentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE status IN ('SENDING', 'RECEIVING')")
    suspend fun getIncomplete(): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}
