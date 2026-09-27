package com.notrace.messenger.attachment.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.notrace.messenger.crypto.data.MessageDirection

enum class AttachmentStatus { PENDING, SENDING, RECEIVING, COMPLETE, FAILED }

enum class AttachmentKind { IMAGE, AUDIO, VIDEO, FILE }

/**
 * Tracks one file transfer, sent or received (Phase 7). id is a
 * sender-generated UUID shared between the attachment-meta and every
 * attachment-chunk envelope, so both sides can correlate them - unlike
 * MessageEntity's auto-increment row id, this must be the SAME value
 * on both devices.
 *
 * completedChunks means "sent" on the OUTGOING side and "received" on
 * the INCOMING side - one field, direction gives it meaning, avoiding
 * two near-duplicate columns.
 *
 * localFileName/thumbnailFileName are keys into EncryptedFileStore
 * (Phase 2), not raw filesystem paths - the file itself is encrypted
 * at rest regardless of transfer direction.
 *
 * expiresAtEpochMillis is schema-only in Phase 7, same as
 * MessageEntity's - no enforcement/UI yet (that's Phase 11).
 */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val contactRandomId: String,
    val direction: MessageDirection,
    val kind: AttachmentKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val totalChunks: Int,
    val completedChunks: Int,
    val status: AttachmentStatus,
    val localFileName: String?,
    val thumbnailFileName: String?,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null
)
