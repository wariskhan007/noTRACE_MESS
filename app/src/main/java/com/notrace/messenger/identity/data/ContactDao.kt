package com.notrace.messenger.identity.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE randomId = :randomId")
    suspend fun get(randomId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ContactEntity)

    @Query("UPDATE contacts SET verificationStatus = :status WHERE randomId = :randomId")
    suspend fun setVerificationStatus(randomId: String, status: VerificationStatus)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE randomId = :randomId")
    suspend fun setBlocked(randomId: String, blocked: Boolean)

    @Query("UPDATE contacts SET localNickname = :nickname WHERE randomId = :randomId")
    suspend fun setNickname(randomId: String, nickname: String?)

    @Query("UPDATE contacts SET disappearingMessageSeconds = :seconds WHERE randomId = :randomId")
    suspend fun setDisappearingMessageSeconds(randomId: String, seconds: Long?)

    @Query("DELETE FROM contacts WHERE randomId = :randomId")
    suspend fun delete(randomId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
