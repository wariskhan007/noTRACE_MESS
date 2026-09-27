package com.notrace.messenger.group.domain

import com.notrace.messenger.group.data.GroupDao
import com.notrace.messenger.group.data.GroupEntity
import com.notrace.messenger.group.data.GroupMemberDao
import com.notrace.messenger.group.data.GroupMemberEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Pure data/membership layer for groups (Phase 10) - creation, member
 * bookkeeping, admin checks. Deliberately has NO knowledge of crypto or
 * the network - that's GroupCryptoManager and GroupCoordinator's job
 * respectively. Mirrors the same layering Phase 3 used for contacts
 * (ContactRepository = data/rules, separate from anything crypto/wire).
 */
class GroupRepository(
    private val groupDao: GroupDao,
    private val memberDao: GroupMemberDao
) {
    fun observeGroups(): Flow<List<GroupEntity>> = groupDao.observeAll()
    fun observeGroup(groupId: String): Flow<GroupEntity?> = groupDao.observe(groupId)
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> = memberDao.observeMembers(groupId)

    suspend fun getGroup(groupId: String): GroupEntity? = groupDao.get(groupId)
    suspend fun getMembers(groupId: String): List<GroupMemberEntity> = memberDao.getMembers(groupId)
    suspend fun isAdmin(groupId: String, memberRandomId: String): Boolean =
        memberDao.getMember(groupId, memberRandomId)?.isAdmin == true

    /** Creates a new group locally with the given members (creator is always admin). Returns the new group's id. */
    suspend fun createGroup(name: String, creatorRandomId: String, memberRandomIds: Set<String>): String {
        val groupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        groupDao.upsert(GroupEntity(id = groupId, name = name, creatorRandomId = creatorRandomId, createdAtEpochMillis = now))
        memberDao.upsert(GroupMemberEntity(groupId, creatorRandomId, isAdmin = true, joinedAtEpochMillis = now))
        memberRandomIds.filter { it != creatorRandomId }.forEach { memberId ->
            memberDao.upsert(GroupMemberEntity(groupId, memberId, isAdmin = false, joinedAtEpochMillis = now))
        }
        return groupId
    }

    /** Records a member as part of a group locally (used both when creating and when this device is ADDED to someone else's group). */
    suspend fun addMemberLocally(groupId: String, memberRandomId: String, isAdmin: Boolean = false) {
        memberDao.upsert(GroupMemberEntity(groupId, memberRandomId, isAdmin, System.currentTimeMillis()))
    }

    suspend fun removeMemberLocally(groupId: String, memberRandomId: String) {
        memberDao.remove(groupId, memberRandomId)
    }

    suspend fun setDisappearingMessageSeconds(groupId: String, seconds: Long?) {
        val group = groupDao.get(groupId) ?: return
        groupDao.upsert(group.copy(disappearingMessageSeconds = seconds))
    }

    /** Full local deletion - group record, all memberships. Caller (GroupCoordinator) handles sender keys and messages separately. */
    suspend fun deleteGroupLocally(groupId: String) {
        memberDao.removeAll(groupId)
        groupDao.delete(groupId)
    }
}
