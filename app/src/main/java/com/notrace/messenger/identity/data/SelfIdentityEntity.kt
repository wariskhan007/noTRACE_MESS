package com.notrace.messenger.identity.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding this device's own identity.
 *
 * randomId is the permanent cryptographic-style anchor (locked decision,
 * Phase 0 #1): a 48-bit SecureRandom value, generated once on first
 * launch and never changed for the life of this install. It is what
 * contacts actually add/verify against.
 *
 * username is a separate, optional, freely-changeable display label
 * (Section 10 "username changes") layered on top — changing it never
 * affects randomId, existing contacts, or any session/verification state.
 *
 * identityEpoch increments only when the identity is fully regenerated:
 * a fresh install, or an explicit "delete account" (Section 10 "account
 * recovery, lost-device handling"). There is deliberately no way to
 * change randomId without bumping identityEpoch — the two always move
 * together, because a randomId change is by definition a new identity.
 *
 * NOTE: this table does NOT yet hold cryptographic identity keys
 * (Curve25519 keypair for X3DH/double-ratchet, etc). Per the plan's own
 * phase split, key generation happens in Phase 4 once the specific
 * mature protocol library is selected — generating protocol-specific
 * keys now, before that choice is made, risks a format that has to be
 * thrown away next phase. This table is the stable account/contact
 * layer Phase 4 attaches crypto material to.
 */
@Entity(tableName = "self_identity")
data class SelfIdentityEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val randomId: String,
    val username: String?,
    val createdAtEpochMillis: Long,
    val identityEpoch: Int
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
