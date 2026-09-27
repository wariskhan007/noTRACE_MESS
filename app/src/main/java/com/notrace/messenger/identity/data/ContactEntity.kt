package com.notrace.messenger.identity.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class VerificationStatus {
    /** Added but the safety-number/fingerprint has not been compared out-of-band yet. */
    UNVERIFIED,
    /** User explicitly confirmed the contact's identity fingerprint matches. */
    VERIFIED,
    /**
     * Reserved for Phase 4+: set when a previously-verified contact's
     * underlying key material changes (their app reinstalled, device
     * lost, etc). Not yet reachable in Phase 3 since no key comparison
     * exists yet — included now so the enum doesn't need a destructive
     * migration later (Section 10 "identity-change warnings").
     */
    NEEDS_REVERIFICATION
}

/**
 * A contact this device knows about, keyed by the contact's randomId
 * (their permanent identity anchor from their own SelfIdentityEntity).
 *
 * localNickname is purely local display text chosen by this user —
 * never sent anywhere, never seen by the contact.
 *
 * lastKnownUsername is a cached snapshot of the contact's self-reported
 * username at the time they were added/last synced. It is NOT trusted
 * for identity — only randomId is (Section 10: usernames are mutable
 * display labels, not identity anchors).
 *
 * verificationStatus and isBlocked are independent: a contact can be
 * blocked whether or not they were ever verified, and blocking does not
 * remove their verification state (so unblocking doesn't lose it).
 *
 * disappearingMessageSeconds (Phase 11) is this 1:1 conversation's
 * timer SETTING - null/0 means off. Kept in sync between both sides
 * via a "timer-update" control envelope whenever either party changes
 * it (see MessagingRepository/ConversationSettingsSync), mirroring how
 * GroupEntity's own field (Phase 10) is synced for groups.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val randomId: String,
    val localNickname: String?,
    val lastKnownUsername: String?,
    val verificationStatus: VerificationStatus,
    val isBlocked: Boolean,
    val addedAtEpochMillis: Long,
    val disappearingMessageSeconds: Long? = null
)
