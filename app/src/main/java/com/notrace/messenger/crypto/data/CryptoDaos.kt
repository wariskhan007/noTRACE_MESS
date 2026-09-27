package com.notrace.messenger.crypto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CryptoSelfIdentityDao {
    @Query("SELECT * FROM crypto_self_identity WHERE id = 0")
    suspend fun get(): CryptoSelfIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CryptoSelfIdentityEntity)
}

@Dao
interface OneTimePreKeyDao {
    @Query("SELECT * FROM one_time_prekeys WHERE preKeyId = :id")
    suspend fun get(id: Int): OneTimePreKeyEntity?

    @Query("SELECT * FROM one_time_prekeys")
    suspend fun getAll(): List<OneTimePreKeyEntity>

    @Query("SELECT * FROM one_time_prekeys WHERE issued = 0 LIMIT 1")
    suspend fun getOneUnissued(): OneTimePreKeyEntity?

    @Query("UPDATE one_time_prekeys SET issued = 1 WHERE preKeyId = :id")
    suspend fun markIssued(id: Int)

    @Query("SELECT COUNT(*) FROM one_time_prekeys WHERE issued = 0")
    suspend fun countUnissued(): Int

    @Query("SELECT MAX(preKeyId) FROM one_time_prekeys")
    suspend fun maxId(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OneTimePreKeyEntity)

    @Query("DELETE FROM one_time_prekeys WHERE preKeyId = :id")
    suspend fun delete(id: Int)
}

@Dao
interface SignedPreKeyDao {
    @Query("SELECT * FROM signed_prekeys WHERE signedPreKeyId = :id")
    suspend fun get(id: Int): SignedPreKeyEntity?

    @Query("SELECT * FROM signed_prekeys ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<SignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignedPreKeyEntity)

    @Query("DELETE FROM signed_prekeys WHERE signedPreKeyId = :id")
    suspend fun delete(id: Int)
}

@Dao
interface RemoteIdentityKeyDao {
    @Query("SELECT * FROM remote_identity_keys WHERE contactRandomId = :contactRandomId")
    suspend fun get(contactRandomId: String): RemoteIdentityKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteIdentityKeyEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE contactRandomId = :contactRandomId")
    suspend fun get(contactRandomId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM sessions WHERE contactRandomId = :contactRandomId)")
    suspend fun exists(contactRandomId: String): Boolean

    @Query("DELETE FROM sessions WHERE contactRandomId = :contactRandomId")
    suspend fun delete(contactRandomId: String)
}
