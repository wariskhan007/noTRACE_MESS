package com.notrace.messenger.group.domain

import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.network.domain.ControlEnvelope
import com.notrace.messenger.network.domain.P2PSessionCoordinator
import com.notrace.messenger.selfdestruct.domain.MessageExpiryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Orchestrates the group wire protocol (Phase 10): creation/invites,
 * Sender Key distribution, sending/receiving group text, and the
 * member-removal/leave key-rotation sequence. This is the layer that
 * ties GroupRepository (data/membership) and GroupCryptoManager
 * (Sender Key crypto) to the transport - every wire operation is just
 * P2PSessionCoordinator.sendEnvelope() to one specific member at a
 * time (fanned out in a loop for group-wide operations), reusing
 * Phase 4-6's entire pairwise encryption/delivery stack unchanged.
 *
 * Envelope kinds (inside the pairwise-encrypted envelope, same layer
 * text/attachment-* already use):
 *   {kind:"group-invite", groupId, groupName, creatorRandomId, memberIds:[...]}
 *   {kind:"group-sender-key-distribution", groupId, distribution}
 *   {kind:"group-text", groupId, ciphertext}
 *   {kind:"group-member-added", groupId, newMemberRandomId}
 *   {kind:"group-member-removed", groupId, removedMemberRandomId}
 *   {kind:"group-member-left", groupId}
 *   {kind:"group-dissolved", groupId}
 *
 * SECURITY-CRITICAL INVARIANT: whenever a member is removed OR leaves,
 * every OTHER remaining member rotates its own Sender Key (deletes the
 * old one, creates a new one, redistributes to the remaining members
 * only - never to the departed member). This is what stops a removed
 * member from continuing to decrypt group messages sent after their
 * removal. See handleMemberRemovedOrLeft.
 */
class GroupCoordinator(
    private val ownRandomId: String,
    private val groupRepository: GroupRepository,
    private val cryptoManager: GroupCryptoManager,
    private val p2pSessionCoordinator: P2PSessionCoordinator,
    private val messageDao: MessageDao,
    private val messageExpiryManager: MessageExpiryManager,
    private val scope: CoroutineScope
) {
    /** Deletes every message for this group AND cascades to their attachment files/thumbnails first (Phase 11 - a plain messageDao.deleteAllForGroup alone would orphan encrypted attachment files on disk). */
    private suspend fun deleteAllGroupContent(groupId: String) {
        val attachmentIds = messageDao.getAttachmentIdsForGroup(groupId)
        attachmentIds.forEach { messageExpiryManager.deleteAttachmentFilesAndRow(it) }
        messageDao.deleteAllForGroup(groupId)
    }

    init {
        p2pSessionCoordinator.controlEnvelopes.onEach { handle(it) }.launchIn(scope)
    }

    /**
     * Creates a group with this device as the (sole, V1) admin, then
     * invites every named member and distributes this device's own
     * initial sender key to each of them.
     */
    fun createGroup(name: String, memberRandomIds: Set<String>) {
        scope.launch {
            val groupId = groupRepository.createGroup(name, ownRandomId, memberRandomIds)
            val allMembers = memberRandomIds + ownRandomId

            val distribution = cryptoManager.createOwnSenderKey(groupId, ownRandomId)

            memberRandomIds.forEach { memberId ->
                p2pSessionCoordinator.sendEnvelope(
                    memberId,
                    JSONObject().apply {
                        put("kind", "group-invite")
                        put("groupId", groupId)
                        put("groupName", name)
                        put("creatorRandomId", ownRandomId)
                        put("memberIds", org.json.JSONArray(allMembers.toList()))
                    }.toString()
                )
                p2pSessionCoordinator.sendEnvelope(
                    memberId,
                    JSONObject().put("kind", "group-sender-key-distribution").put("groupId", groupId).put("distribution", distribution).toString()
                )
            }
        }
    }

    fun observeGroupConversation(groupId: String) = messageDao.observeGroupConversation(groupId)

    /** Encrypts with this device's Sender Key once, fans the same ciphertext out pairwise to every other current member. */
    fun sendGroupText(groupId: String, plaintext: String) {
        scope.launch {
            val members = groupRepository.getMembers(groupId)
            val ciphertext = cryptoManager.encrypt(groupId, ownRandomId, plaintext.toByteArray(Charsets.UTF_8))

            members.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                p2pSessionCoordinator.sendEnvelope(
                    member.memberRandomId,
                    JSONObject().put("kind", "group-text").put("groupId", groupId).put("ciphertext", ciphertext).toString()
                )
            }

            val expiresAt = groupRepository.getGroup(groupId)?.disappearingMessageSeconds?.let { System.currentTimeMillis() + it * 1000 }
            messageDao.insert(
                MessageEntity(
                    contactRandomId = ownRandomId, groupId = groupId, direction = MessageDirection.OUTGOING,
                    body = plaintext, sentOrReceivedAtEpochMillis = System.currentTimeMillis(), deliveryState = DeliveryState.SENT,
                    expiresAtEpochMillis = expiresAt
                )
            )
        }
    }

    /** Admin-only. Adds a member: existing members each send them their CURRENT (not rotated) sender key - see class doc on why no rotation is needed for an addition. */
    fun addMember(groupId: String, newMemberRandomId: String) {
        scope.launch {
            if (!groupRepository.isAdmin(groupId, ownRandomId)) return@launch
            val group = groupRepository.getGroup(groupId) ?: return@launch
            val existingMembers = groupRepository.getMembers(groupId)

            groupRepository.addMemberLocally(groupId, newMemberRandomId, isAdmin = false)

            // Tell the new member who's already in the group.
            p2pSessionCoordinator.sendEnvelope(
                newMemberRandomId,
                JSONObject().apply {
                    put("kind", "group-invite")
                    put("groupId", groupId)
                    put("groupName", group.name)
                    put("creatorRandomId", group.creatorRandomId)
                    put("memberIds", org.json.JSONArray((existingMembers.map { it.memberRandomId } + newMemberRandomId)))
                }.toString()
            )
            // The new member needs OUR current sender key state to read
            // future messages from us - NOT a rotated one (that would
            // break every EXISTING member who hasn't been told about a
            // rotation). See GroupCryptoManager.getOrCreateOwnSenderKeyDistribution.
            val ownDistribution = cryptoManager.getOrCreateOwnSenderKeyDistribution(groupId, ownRandomId)
            p2pSessionCoordinator.sendEnvelope(
                newMemberRandomId,
                JSONObject().put("kind", "group-sender-key-distribution").put("groupId", groupId).put("distribution", ownDistribution).toString()
            )
            // Tell every EXISTING member about the addition, so they each
            // also send the new member their own current sender key.
            existingMembers.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                p2pSessionCoordinator.sendEnvelope(
                    member.memberRandomId,
                    JSONObject().put("kind", "group-member-added").put("groupId", groupId).put("newMemberRandomId", newMemberRandomId).toString()
                )
            }
        }
    }

    /** Admin-only. Removes a member and triggers the security-critical rotation (see class doc). */
    fun removeMember(groupId: String, memberRandomId: String) {
        scope.launch {
            if (!groupRepository.isAdmin(groupId, ownRandomId)) return@launch
            groupRepository.removeMemberLocally(groupId, memberRandomId)
            val remainingMembers = groupRepository.getMembers(groupId)

            remainingMembers.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                p2pSessionCoordinator.sendEnvelope(
                    member.memberRandomId,
                    JSONObject().put("kind", "group-member-removed").put("groupId", groupId).put("removedMemberRandomId", memberRandomId).toString()
                )
            }
            // Tell the removed member too, so their client stops showing the group as active.
            p2pSessionCoordinator.sendEnvelope(memberRandomId, JSONObject().put("kind", "group-member-removed").put("groupId", groupId).put("removedMemberRandomId", memberRandomId).toString())

            rotateAndRedistribute(groupId, remainingMembers.map { it.memberRandomId }.filter { it != ownRandomId })
        }
    }

    fun leaveGroup(groupId: String) {
        scope.launch {
            val members = groupRepository.getMembers(groupId)
            members.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                p2pSessionCoordinator.sendEnvelope(member.memberRandomId, JSONObject().put("kind", "group-member-left").put("groupId", groupId).toString())
            }
            cryptoManager.deleteAllSenderKeysForGroup(groupId)
            groupRepository.deleteGroupLocally(groupId)
            deleteAllGroupContent(groupId)
        }
    }

    /** Admin-only. Notifies every member the group is gone, then deletes locally. */
    fun dissolveGroup(groupId: String) {
        scope.launch {
            if (!groupRepository.isAdmin(groupId, ownRandomId)) return@launch
            val members = groupRepository.getMembers(groupId)
            members.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                p2pSessionCoordinator.sendEnvelope(member.memberRandomId, JSONObject().put("kind", "group-dissolved").put("groupId", groupId).toString())
            }
            cryptoManager.deleteAllSenderKeysForGroup(groupId)
            groupRepository.deleteGroupLocally(groupId)
            deleteAllGroupContent(groupId)
        }
    }

    fun setDisappearingMessageSeconds(groupId: String, seconds: Long?) {
        scope.launch {
            if (!groupRepository.isAdmin(groupId, ownRandomId)) return@launch // Section 10: only the admin sets group-wide policy
            groupRepository.setDisappearingMessageSeconds(groupId, seconds)
            val members = groupRepository.getMembers(groupId)
            members.filter { it.memberRandomId != ownRandomId }.forEach { member ->
                val envelope = JSONObject().put("kind", "group-timer-update").put("groupId", groupId)
                if (seconds != null) envelope.put("seconds", seconds) else envelope.put("seconds", JSONObject.NULL)
                p2pSessionCoordinator.sendEnvelope(member.memberRandomId, envelope.toString())
            }
        }
    }

    private fun handle(envelope: ControlEnvelope) {
        val json = envelope.json
        val groupId = json.optString("groupId")
        when (envelope.kind) {
            "group-invite" -> scope.launch {
                val name = json.getString("groupName")
                val creatorId = json.getString("creatorRandomId")
                val memberIds = json.getJSONArray("memberIds").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                if (groupRepository.getGroup(groupId) == null) {
                    groupRepository.createGroup(name, creatorId, memberIds.toSet())
                    // createGroup always makes creatorId the sole admin, correctly
                    // reflecting that WE are not the admin of a group we were invited to.
                }
            }
            "group-sender-key-distribution" -> scope.launch {
                cryptoManager.processDistribution(groupId, envelope.contactRandomId, json.getString("distribution"))
            }
            "group-text" -> scope.launch {
                val plaintextBytes = cryptoManager.decrypt(groupId, envelope.contactRandomId, json.getString("ciphertext"))
                if (plaintextBytes != null) {
                    val expiresAt = groupRepository.getGroup(groupId)?.disappearingMessageSeconds?.let { System.currentTimeMillis() + it * 1000 }
                    messageDao.insert(
                        MessageEntity(
                            contactRandomId = envelope.contactRandomId, groupId = groupId, direction = MessageDirection.INCOMING,
                            body = String(plaintextBytes, Charsets.UTF_8), sentOrReceivedAtEpochMillis = System.currentTimeMillis(),
                            deliveryState = DeliveryState.DELIVERED, expiresAtEpochMillis = expiresAt
                        )
                    )
                }
                // If null: no sender key on file for this member yet (missed
                // their distribution message) - a disclosed limitation, see
                // GroupCryptoManager's class doc; this message is simply lost
                // rather than crashing or corrupting state.
            }
            "group-member-added" -> scope.launch {
                val newMemberId = json.getString("newMemberRandomId")
                groupRepository.addMemberLocally(groupId, newMemberId, isAdmin = false)
                // Send the new member OUR current sender key state (not a
                // rotation - see getOrCreateOwnSenderKeyDistribution's doc).
                val distribution = cryptoManager.getOrCreateOwnSenderKeyDistribution(groupId, ownRandomId)
                p2pSessionCoordinator.sendEnvelope(
                    newMemberId,
                    JSONObject().put("kind", "group-sender-key-distribution").put("groupId", groupId).put("distribution", distribution).toString()
                )
            }
            "group-member-removed" -> scope.launch {
                val removedId = json.getString("removedMemberRandomId")
                if (removedId == ownRandomId) {
                    // We were the one removed - forget everything about this group.
                    cryptoManager.deleteAllSenderKeysForGroup(groupId)
                    groupRepository.deleteGroupLocally(groupId)
                    deleteAllGroupContent(groupId)
                } else {
                    groupRepository.removeMemberLocally(groupId, removedId)
                    val remaining = groupRepository.getMembers(groupId).map { it.memberRandomId }.filter { it != ownRandomId }
                    rotateAndRedistribute(groupId, remaining)
                }
            }
            "group-member-left" -> scope.launch {
                groupRepository.removeMemberLocally(groupId, envelope.contactRandomId)
                val remaining = groupRepository.getMembers(groupId).map { it.memberRandomId }.filter { it != ownRandomId }
                rotateAndRedistribute(groupId, remaining)
            }
            "group-dissolved" -> scope.launch {
                cryptoManager.deleteAllSenderKeysForGroup(groupId)
                groupRepository.deleteGroupLocally(groupId)
                deleteAllGroupContent(groupId)
            }
            "group-timer-update" -> scope.launch {
                // Only accept this from the group's actual admin (the
                // creator) - otherwise any member could forge a policy
                // change for everyone else. envelope.contactRandomId is
                // the PAIRWISE session's verified sender, not a claim
                // inside the payload itself, so this can't be spoofed
                // without also compromising that member's session.
                val group = groupRepository.getGroup(groupId) ?: return@launch
                if (envelope.contactRandomId != group.creatorRandomId) return@launch
                val seconds = if (json.isNull("seconds")) null else json.optLong("seconds")
                groupRepository.setDisappearingMessageSeconds(groupId, seconds)
            }
        }
    }

    /** The security-critical operation: fresh sender key, sent only to the given (already-updated) member list. */
    private suspend fun rotateAndRedistribute(groupId: String, remainingOtherMembers: List<String>) {
        cryptoManager.deleteOwnSenderKey(groupId, ownRandomId)
        val freshDistribution = cryptoManager.createOwnSenderKey(groupId, ownRandomId)
        remainingOtherMembers.forEach { memberId ->
            p2pSessionCoordinator.sendEnvelope(
                memberId,
                JSONObject().put("kind", "group-sender-key-distribution").put("groupId", groupId).put("distribution", freshDistribution).toString()
            )
        }
    }
}
