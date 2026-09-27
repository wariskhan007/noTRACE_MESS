package com.notrace.messenger.group.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun get(id: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observe(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND memberRandomId = :memberRandomId")
    suspend fun getMember(groupId: String, memberRandomId: String): GroupMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberRandomId = :memberRandomId")
    suspend fun remove(groupId: String, memberRandomId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun removeAll(groupId: String)
}

@Dao
interface GroupSenderKeyDao {
    @Query("SELECT * FROM group_sender_keys WHERE groupId = :groupId AND senderRandomId = :senderRandomId")
    suspend fun get(groupId: String, senderRandomId: String): GroupSenderKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupSenderKeyEntity)

    @Query("DELETE FROM group_sender_keys WHERE groupId = :groupId AND senderRandomId = :senderRandomId")
    suspend fun delete(groupId: String, senderRandomId: String)

    @Query("DELETE FROM group_sender_keys WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: String)
}
