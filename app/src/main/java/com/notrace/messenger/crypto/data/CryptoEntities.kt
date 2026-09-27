package com.notrace.messenger.crypto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * This device's own Signal Protocol identity: a long-term Curve25519
 * identity key pair plus the random registrationId libsignal requires.
 * Singleton row, separate from Phase 3's SelfIdentityEntity (which
 * holds the display-layer randomId/username) - this is the actual
 * cryptographic material Phase 0 called out as its own data-inventory
 * item ("Identity keys"), generated now that the protocol is chosen.
 *
 * identityKeyPairBytes is IdentityKeyPair.serialize() - private+public
 * key material. It lives only inside the SQLCipher-encrypted database
 * (Phase 2), never in logs, never transmitted.
 */
@Entity(tableName = "crypto_self_identity")
data class CryptoSelfIdentityEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val identityKeyPairBytes: ByteArray,
    val registrationId: Int,
    val createdAtEpochMillis: Long
) {
    companion object { const val SINGLETON_ID = 0 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CryptoSelfIdentityEntity) return false
        return id == other.id &&
            identityKeyPairBytes.contentEquals(other.identityKeyPairBytes) &&
            registrationId == other.registrationId &&
            createdAtEpochMillis == other.createdAtEpochMillis
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + identityKeyPairBytes.contentHashCode()
        result = 31 * result + registrationId
        result = 31 * result + createdAtEpochMillis.hashCode()
        return result
    }
}

/**
 * One local one-time prekey. Consumed (deleted) by libsignal itself,
 * automatically, when this device later decrypts the recipient's first
 * PreKeySignalMessage that used it - NOT at export time.
 *
 * `issued` marks a prekey as already handed out in an exported bundle
 * (CryptoIdentityManager.takeOneOneTimePreKey), so a second export
 * doesn't offer the same prekey to a second contact before the first
 * contact has ever used it. The row itself is untouched by issuing -
 * it must remain loadable via PreKeyStore.loadPreKey until libsignal's
 * own removePreKey() call deletes it for real.
 */
@Entity(tableName = "one_time_prekeys")
data class OneTimePreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val recordBytes: ByteArray,
    val issued: Boolean = false
) {
    override fun equals(other: Any?) = other is OneTimePreKeyEntity &&
        preKeyId == other.preKeyId && recordBytes.contentEquals(other.recordBytes)
    override fun hashCode() = 31 * preKeyId + recordBytes.contentHashCode()
}

/**
 * Signed prekeys are rotated periodically (Section 5 "key rotation"),
 * unlike one-time prekeys they aren't deleted on first use - only when
 * superseded by a newer one, so a session started against an
 * about-to-rotate key can still complete.
 */
@Entity(tableName = "signed_prekeys")
data class SignedPreKeyEntity(
    @PrimaryKey val signedPreKeyId: Int,
    val recordBytes: ByteArray,
    val createdAtEpochMillis: Long
) {
    override fun equals(other: Any?) = other is SignedPreKeyEntity &&
        signedPreKeyId == other.signedPreKeyId && recordBytes.contentEquals(other.recordBytes)
    override fun hashCode() = 31 * signedPreKeyId + recordBytes.contentHashCode()
}

/**
 * A remote contact's identity key, as first observed. Used to detect
 * identity changes (Section 10 "identity-change warnings"): if a
 * contact's key ever changes, this table is how we know, and the
 * contact's VerificationStatus (Phase 3) is downgraded to
 * NEEDS_REVERIFICATION rather than silently trusting the new key.
 */
@Entity(tableName = "remote_identity_keys")
data class RemoteIdentityKeyEntity(
    @PrimaryKey val contactRandomId: String,
    val identityKeyBytes: ByteArray,
    val firstSeenAtEpochMillis: Long
) {
    override fun equals(other: Any?) = other is RemoteIdentityKeyEntity &&
        contactRandomId == other.contactRandomId && identityKeyBytes.contentEquals(other.identityKeyBytes)
    override fun hashCode() = 31 * contactRandomId.hashCode() + identityKeyBytes.contentHashCode()
}

/**
 * Serialized Double Ratchet session state for one contact
 * (SessionRecord.serialize()). One row per contact in this V1 (single
 * device per identity, Phase 0 decision #8 - no sub-device sessions).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val contactRandomId: String,
    val sessionRecordBytes: ByteArray,
    val updatedAtEpochMillis: Long
) {
    override fun equals(other: Any?) = other is SessionEntity &&
        contactRandomId == other.contactRandomId && sessionRecordBytes.contentEquals(other.sessionRecordBytes)
    override fun hashCode() = 31 * contactRandomId.hashCode() + sessionRecordBytes.contentHashCode()
}
