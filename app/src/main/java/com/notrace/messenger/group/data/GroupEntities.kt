package com.notrace.messenger.group.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group's identity (Phase 10: "group identity, membership,
 * administration"). Purely local truth, like everything else in this
 * app - there is no group-membership server, so "the group" is really
 * "what this device currently believes about the group," kept in sync
 * by the member-added/member-removed/dissolved control messages each
 * member fans out pairwise (see GroupCoordinator).
 *
 * creatorRandomId is fixed at creation and is the sole admin for V1
 * (a bounded, disclosed scope cut - multi-admin support is a natural
 * follow-up, not built here). disappearingMessageSeconds is a
 * schema-only SETTING in this phase (mirrors Phase 4's
 * MessageEntity.expiresAtEpochMillis pattern) - Phase 11 is where
 * actual timed deletion gets built and starts reading this field.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creatorRandomId: String,
    val createdAtEpochMillis: Long,
    val disappearingMessageSeconds: Long? = null
)

/**
 * One membership row per (group, member). isAdmin is true only for the
 * creator in V1. deviceId is always 1 - single-device-per-identity
 * (Phase 0 decision #8) applies to group members too.
 */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "memberRandomId"])
data class GroupMemberEntity(
    val groupId: String,
    val memberRandomId: String,
    val isAdmin: Boolean,
    val joinedAtEpochMillis: Long
)

/**
 * Serialized SenderKeyRecord state (libsignal's group.SenderKeyRecord),
 * one row per (group, sender) this device knows a sender key for -
 * including this device's OWN sender key for each group it's in
 * (senderRandomId = self's randomId in that case).
 *
 * This is what gets deleted and regenerated on member removal/leave
 * (GroupCryptoManager.rotateOwnSenderKey) - the single most
 * security-critical operation in the group feature: without this
 * rotation, a removed member's already-distributed sender key
 * knowledge would let them keep decrypting messages from members who
 * never rotated, forever.
 */
@Entity(tableName = "group_sender_keys", primaryKeys = ["groupId", "senderRandomId"])
data class GroupSenderKeyEntity(
    val groupId: String,
    val senderRandomId: String,
    val recordBytes: ByteArray
) {
    override fun equals(other: Any?) = other is GroupSenderKeyEntity &&
        groupId == other.groupId && senderRandomId == other.senderRandomId && recordBytes.contentEquals(other.recordBytes)
    override fun hashCode() = 31 * (31 * groupId.hashCode() + senderRandomId.hashCode()) + recordBytes.contentHashCode()
}
