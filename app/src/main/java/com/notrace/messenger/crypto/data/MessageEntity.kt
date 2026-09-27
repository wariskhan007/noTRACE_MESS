package com.notrace.messenger.crypto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageDirection { INCOMING, OUTGOING }

enum class DeliveryState {
    /** Encrypted and stored locally, not yet handed to a transport (no transport exists until Phase 5). */
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}

/**
 * A single decrypted message, stored in plaintext form here
 * intentionally: the *database file itself* is already encrypted at
 * rest by SQLCipher (Phase 2), so this is the same model Signal itself
 * uses (decrypt on receipt, store decrypted, encrypted-at-rest). The
 * "never log plaintext" rule (Section 6) is about Logcat/log files, not
 * this encrypted database - kept out of any log statement everywhere
 * in this codebase.
 *
 * expiresAtEpochMillis is schema-only in Phase 4: the column exists so
 * a later phase's disappearing-message timer (plan's Phase 11) doesn't
 * need a migration, but nothing reads/enforces it yet (plan rule #12:
 * "keep incomplete features disabled in release builds" - there is no
 * UI to set it, and no cleanup worker runs against it yet).
 *
 * attachmentId (Phase 7) links this row to an AttachmentEntity when
 * this "message" is actually a file transfer rather than text - body
 * holds a human-readable placeholder (the file name) for list previews
 * so no UI code needs to special-case a null body; the attachment
 * card's real content (thumbnail/progress/open action) is rendered by
 * looking up attachmentId, not by parsing body.
 *
 * groupId (Phase 10) is null for a normal 1:1 message. For a group
 * message, groupId identifies the conversation and contactRandomId is
 * repurposed to mean "the individual member who sent/is credited with
 * this copy" (the sender's own randomId for both directions - a
 * member always logs their own outgoing group message under their own
 * id, same as any other member would see it under that sender's id).
 * The two fields are never both meaningful at once: a 1:1 conversation
 * query filters `groupId IS NULL AND contactRandomId = :id`; a group
 * conversation query filters `groupId = :id`.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactRandomId: String,
    val direction: MessageDirection,
    val body: String,
    val sentOrReceivedAtEpochMillis: Long,
    val deliveryState: DeliveryState,
    val expiresAtEpochMillis: Long? = null,
    val attachmentId: String? = null,
    val groupId: String? = null
)
