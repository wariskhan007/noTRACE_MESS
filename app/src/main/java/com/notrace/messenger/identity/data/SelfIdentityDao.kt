package com.notrace.messenger.identity.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SelfIdentityDao {
    @Query("SELECT * FROM self_identity WHERE id = 0")
    suspend fun get(): SelfIdentityEntity?

    @Query("SELECT * FROM self_identity WHERE id = 0")
    fun observe(): Flow<SelfIdentityEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SelfIdentityEntity)

    @Query("DELETE FROM self_identity")
    suspend fun deleteAll()
}
