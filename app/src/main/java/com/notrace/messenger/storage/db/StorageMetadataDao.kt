package com.notrace.messenger.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StorageMetadataDao {
    @Query("SELECT * FROM storage_metadata WHERE id = 0")
    suspend fun get(): StorageMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StorageMetadataEntity)
}
