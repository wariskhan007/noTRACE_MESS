package com.notrace.messenger.group.domain

import android.util.Base64
import com.notrace.messenger.group.data.GroupSenderKeyDao
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.groups.SenderKeyName
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

/**
 * Wraps libsignal's Sender Key group-crypto primitives (Phase 10 -
 * the standard, non-invented answer to "1:1 Double Ratchet doesn't
 * extend to groups" - this is literally what Signal's own app uses).
 *
 * How it fits together with the rest of the app: a Sender Key
 * ciphertext (GroupCipher.encrypt output) is itself already encrypted
 * and authenticated - but distributing the KEY that lets others decrypt
 * it, and transporting the resulting ciphertext to each member, both
 * reuse Phase 4-6's existing PAIRWISE Double Ratchet transport
 * end-to-end (see GroupCoordinator) - no new transport/signaling
 * mechanism was built for groups, only a new content-encryption layer
 * riding on top of the existing one. This also means the signaling
 * server cannot distinguish a group message from a 1:1 message at all
 * (extra metadata minimization, not just reuse for its own sake).
 *
 * MOST SECURITY-CRITICAL METHOD: rotateOwnSenderKey. When a member is
 * removed (or leaves), every REMAINING member must call this - it
 * deletes the old key and creates a fresh one, so the departed member's
 * copy of the old key becomes useless for any message sent after
 * rotation. Skipping this (or only doing it for future new-member
 * additions instead of removals) is the exact bug class that let a
 * removed member keep reading a group forever in an earlier iteration
 * of this project - it must never regress.
 *
 * HIGH RISK-OF-DRIFT NOTE: same caveat as GroupSenderKeyStore - group
 * API method names/signatures (GroupSessionBuilder.create/process,
 * GroupCipher.encrypt/decrypt) could not be verified against a real
 * compile of this libsignal-android version in this sandbox.
 */
class GroupCryptoManager(
    private val senderKeyDao: GroupSenderKeyDao,
    private val senderKeyStore: GroupSenderKeyStore
) {
    private val sessionBuilder = GroupSessionBuilder(senderKeyStore)

    private fun senderKeyName(groupId: String, memberRandomId: String) =
        SenderKeyName(groupId, SignalProtocolAddress(memberRandomId, 1))

    /**
     * Creates this device's FIRST-EVER sender key for a group. Only
     * call this when NO key exists yet (first join/creation) OR when
     * deliberately rotating after a member removal (rotateOwnSenderKey
     * in GroupCoordinator always deletes the old key first, so this
     * always starts genuinely fresh in that case too). Returns the
     * distribution message to send to every other current member.
     */
    fun createOwnSenderKey(groupId: String, ownRandomId: String): String {
        val name = senderKeyName(groupId, ownRandomId)
        val distribution = sessionBuilder.create(name)
        return Base64.encodeToString(distribution.serialize(), Base64.NO_WRAP)
    }

    /**
     * Returns a distribution message for this device's CURRENT group
     * sender key state - creating a brand new key only if none exists
     * yet, otherwise reading the EXISTING record's current chain
     * position without advancing or replacing it.
     *
     * This distinction matters a lot: this is the method to call when
     * a NEW member is added to a group this device is already in - the
     * new member needs to be able to decrypt messages sent from this
     * point forward, using whatever chain state is CURRENT right now.
     * Calling createOwnSenderKey() instead here would silently
     * generate a brand-new chain, which every EXISTING member would
     * then fail to decrypt against unless separately re-notified - a
     * real bug this method exists specifically to avoid.
     *
     * HIGHEST-UNCERTAINTY SPOT IN THE GROUP FEATURE: constructing a
     * SenderKeyDistributionMessage from an existing SenderKeyRecord's
     * current state (rather than via GroupSessionBuilder.create, which
     * always mints a fresh chain) uses accessor names
     * (getSenderKeyState/senderChainKey/signingKey etc.) that could not
     * be verified against a real compile of this libsignal-android
     * version in this sandbox. If this specific method fails to
     * compile, the fix is almost certainly a narrow accessor-name
     * correction here - the SURROUNDING logic (never rotate on a mere
     * addition) is the part that must be preserved exactly.
     */
    suspend fun getOrCreateOwnSenderKeyDistribution(groupId: String, ownRandomId: String): String {
        val existing = senderKeyDao.get(groupId, ownRandomId)
        if (existing == null) {
            return createOwnSenderKey(groupId, ownRandomId)
        }

        val record = SenderKeyRecord(existing.recordBytes)
        val state = record.senderKeyState
        val distribution = SenderKeyDistributionMessage(
            state.keyId,
            state.senderChainKey.iteration,
            state.senderChainKey.seed,
            state.signingKeyPublic
        )
        return Base64.encodeToString(distribution.serialize(), Base64.NO_WRAP)
    }

    /** Processes a distribution message received from another member, enabling decryption of their future group ciphertext. */
    fun processDistribution(groupId: String, fromMemberRandomId: String, encodedDistribution: String) {
        val name = senderKeyName(groupId, fromMemberRandomId)
        val bytes = Base64.decode(encodedDistribution, Base64.NO_WRAP)
        val distribution = SenderKeyDistributionMessage(bytes)
        sessionBuilder.process(name, distribution)
    }

    fun encrypt(groupId: String, ownRandomId: String, plaintext: ByteArray): String {
        val cipher = GroupCipher(senderKeyStore, senderKeyName(groupId, ownRandomId))
        return Base64.encodeToString(cipher.encrypt(plaintext), Base64.NO_WRAP)
    }

    /** Returns null if this device has no sender key on file for that member yet (never received their distribution message). */
    suspend fun decrypt(groupId: String, fromMemberRandomId: String, encodedCiphertext: String): ByteArray? {
        if (senderKeyDao.get(groupId, fromMemberRandomId) == null) return null
        val cipher = GroupCipher(senderKeyStore, senderKeyName(groupId, fromMemberRandomId))
        val bytes = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
        return cipher.decrypt(bytes)
    }

    /**
     * Deletes this device's own sender key for a group so the next
     * createOwnSenderKey() call starts a genuinely fresh chain (rather
     * than resuming forward from a state a soon-to-be-excluded member
     * might already know a checkpoint of). Called by GroupCoordinator
     * whenever a member is removed/leaves - see class doc.
     */
    suspend fun deleteOwnSenderKey(groupId: String, ownRandomId: String) {
        senderKeyDao.delete(groupId, ownRandomId)
    }

    /** Deletes ALL sender key state (own and every other member's) for a group - used when dissolving/leaving a group entirely. */
    suspend fun deleteAllSenderKeysForGroup(groupId: String) {
        senderKeyDao.deleteAllForGroup(groupId)
    }
}
