package com.notrace.messenger.attachment.domain

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.notrace.messenger.attachment.data.AttachmentDao
import com.notrace.messenger.attachment.data.AttachmentEntity
import com.notrace.messenger.attachment.data.AttachmentKind
import com.notrace.messenger.attachment.data.AttachmentStatus
import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.network.domain.ControlEnvelope
import com.notrace.messenger.network.domain.P2PSessionCoordinator
import com.notrace.messenger.storage.files.EncryptedFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Orchestrates chunked, encrypted file transfer (Phase 7). Every chunk
 * is just another envelope through P2PSessionCoordinator.sendEnvelope -
 * this reuses the ENTIRE existing crypto/transport stack (Signal
 * Protocol encryption, live data channel, Phase 6 relay fallback)
 * rather than inventing a separate file-transfer channel or protocol.
 *
 * Known, documented limitations (honest scope for this phase):
 *  - In-progress receive buffers are held in memory (a Map of chunk
 *    byte arrays), not persisted to disk incrementally. A process
 *    death mid-transfer loses that attachment's progress and it must
 *    restart from scratch - full crash-resumability would need
 *    per-chunk disk persistence, which is a reasonable Phase 12
 *    (performance/reliability) follow-up, not built here.
 *  - Chunks must currently arrive and be buffered even if out of
 *    order (buffered by index), but a full attachment can only be
 *    reassembled once every chunk has arrived at least once - there is
 *    no retry/request-missing-chunk mechanism yet.
 *  - The recipient gets no preview until the transfer completes and
 *    passes its integrity check (no progressive/streaming thumbnail).
 */
class AttachmentTransferManager(
    private val context: Context,
    private val coordinator: P2PSessionCoordinator,
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
    private val encryptedFileStore: EncryptedFileStore,
    private val scope: CoroutineScope
) {
    /** attachmentId -> per-chunk buffer, sized totalChunks, nulls for not-yet-arrived chunks. Receiving side only. */
    private val receiveBuffers = mutableMapOf<String, Array<ByteArray?>>()

    init {
        coordinator.controlEnvelopes.onEach { handleControlEnvelope(it) }.launchIn(scope)
    }

    fun observeAttachment(id: String): Flow<AttachmentEntity?> = attachmentDao.observe(id)

    /** Reads the picked file fully into memory, then delegates to sendAttachmentBytes. */
    fun sendAttachment(contactRandomId: String, uri: Uri) {
        scope.launch {
            val (bytes, fileName, mimeType) = withContext(Dispatchers.IO) { readPickedFile(uri) }
                ?: return@launch // couldn't read it; nothing to report to yet since no AttachmentEntity exists
            sendAttachmentBytesInternal(contactRandomId, bytes, fileName, mimeType)
        }
    }

    /**
     * Core send path, shared by sendAttachment (file picker, Phase 7)
     * and voice notes (Phase 8's ChatScreen recorder hands its raw
     * recorded bytes here directly - no need to round-trip through a
     * content:// Uri/FileProvider just to reuse this path).
     */
    fun sendAttachmentBytes(contactRandomId: String, bytes: ByteArray, fileName: String, mimeType: String) {
        scope.launch { sendAttachmentBytesInternal(contactRandomId, bytes, fileName, mimeType) }
    }

    private suspend fun sendAttachmentBytesInternal(contactRandomId: String, bytes: ByteArray, fileName: String, mimeType: String) {
            if (bytes.size > AttachmentCodec.MAX_ATTACHMENT_BYTES) {
                return // TODO surface a user-facing error via a status flow, same pattern as ChatViewModel.statusMessage
            }
            if (!hasEnoughFreeSpace(bytes.size.toLong())) {
                return // low storage (plan Section 12) - refuse before writing anything rather than failing partway through
            }

            val id = UUID.randomUUID().toString()
            val sha256 = AttachmentCodec.sha256Hex(bytes)
            val totalChunks = AttachmentCodec.chunkCount(bytes.size.toLong())
            val kind = kindForMimeType(mimeType)

            attachmentDao.upsert(
                AttachmentEntity(
                    id = id, contactRandomId = contactRandomId, direction = MessageDirection.OUTGOING,
                    kind = kind, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(),
                    sha256Hex = sha256, totalChunks = totalChunks, completedChunks = 0,
                    status = AttachmentStatus.SENDING, localFileName = null, thumbnailFileName = null,
                    createdAtEpochMillis = System.currentTimeMillis()
                )
            )
            messageDao.insert(
                MessageEntity(
                    contactRandomId = contactRandomId, direction = MessageDirection.OUTGOING, body = fileName,
                    sentOrReceivedAtEpochMillis = System.currentTimeMillis(), deliveryState = DeliveryState.PENDING,
                    attachmentId = id
                )
            )

            val metaEnvelope = JSONObject().apply {
                put("kind", "attachment-meta")
                put("attachmentId", id)
                put("fileName", fileName)
                put("mimeType", mimeType)
                put("sizeBytes", bytes.size)
                put("sha256", sha256)
                put("totalChunks", totalChunks)
            }
            if (!coordinator.sendEnvelope(contactRandomId, metaEnvelope.toString())) {
                attachmentDao.upsert(currentOrFail(id).copy(status = AttachmentStatus.FAILED))
                return
            }

            for (index in 0 until totalChunks) {
                val start = index * AttachmentCodec.CHUNK_SIZE_BYTES
                val end = minOf(start + AttachmentCodec.CHUNK_SIZE_BYTES, bytes.size)
                val chunkEnvelope = JSONObject().apply {
                    put("kind", "attachment-chunk")
                    put("attachmentId", id)
                    put("chunkIndex", index)
                    put("data", AttachmentCodec.encodeChunk(bytes.copyOfRange(start, end)))
                }
                val sent = coordinator.sendEnvelope(contactRandomId, chunkEnvelope.toString())
                if (!sent) {
                    attachmentDao.upsert(currentOrFail(id).copy(status = AttachmentStatus.FAILED))
                    return
                }
                attachmentDao.upsert(currentOrFail(id).copy(completedChunks = index + 1))
            }

            attachmentDao.upsert(currentOrFail(id).copy(status = AttachmentStatus.COMPLETE))
    }

    private suspend fun currentOrFail(id: String): AttachmentEntity =
        attachmentDao.get(id) ?: error("Attachment $id vanished mid-transfer")

    private fun handleControlEnvelope(envelope: ControlEnvelope) {
        when (envelope.kind) {
            "attachment-meta" -> scope.launch { handleAttachmentMeta(envelope.contactRandomId, envelope.json) }
            "attachment-chunk" -> scope.launch { handleAttachmentChunk(envelope.json) }
        }
    }

    private suspend fun handleAttachmentMeta(contactRandomId: String, json: JSONObject) {
        val id = json.getString("attachmentId")
        val sizeBytes = json.getLong("sizeBytes")
        if (sizeBytes > AttachmentCodec.MAX_ATTACHMENT_BYTES || sizeBytes < 0) {
            return // refuse to even allocate a buffer for an oversized/invalid announcement
        }
        if (!hasEnoughFreeSpace(sizeBytes)) {
            return // low storage (plan Section 12) - don't start receiving something we can't fit
        }
        val totalChunks = json.getInt("totalChunks")
        val fileName = json.getString("fileName")
        val mimeType = json.getString("mimeType")

        receiveBuffers[id] = arrayOfNulls(totalChunks)

        attachmentDao.upsert(
            AttachmentEntity(
                id = id, contactRandomId = contactRandomId, direction = MessageDirection.INCOMING,
                kind = kindForMimeType(mimeType), fileName = fileName, mimeType = mimeType, sizeBytes = sizeBytes,
                sha256Hex = json.getString("sha256"), totalChunks = totalChunks, completedChunks = 0,
                status = AttachmentStatus.RECEIVING, localFileName = null, thumbnailFileName = null,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
        messageDao.insert(
            MessageEntity(
                contactRandomId = contactRandomId, direction = MessageDirection.INCOMING, body = fileName,
                sentOrReceivedAtEpochMillis = System.currentTimeMillis(), deliveryState = DeliveryState.DELIVERED,
                attachmentId = id
            )
        )
    }

    private suspend fun handleAttachmentChunk(json: JSONObject) {
        val id = json.getString("attachmentId")
        val buffer = receiveBuffers[id] ?: return // meta never arrived or already completed/discarded
        val index = json.getInt("chunkIndex")
        if (index !in buffer.indices) return
        buffer[index] = AttachmentCodec.decodeChunk(json.getString("data"))

        val received = buffer.count { it != null }
        val entity = attachmentDao.get(id) ?: return
        attachmentDao.upsert(entity.copy(completedChunks = received))

        if (buffer.none { it == null }) {
            finishReceiving(id, buffer)
        }
    }

    private suspend fun finishReceiving(id: String, buffer: Array<ByteArray?>) {
        val entity = attachmentDao.get(id) ?: return
        val assembled = withContext(Dispatchers.Default) {
            val out = java.io.ByteArrayOutputStream(entity.sizeBytes.toInt().coerceAtLeast(0))
            buffer.forEach { chunk -> out.write(chunk!!) }
            out.toByteArray()
        }
        receiveBuffers.remove(id)

        val actualHash = AttachmentCodec.sha256Hex(assembled)
        if (actualHash != entity.sha256Hex) {
            attachmentDao.upsert(entity.copy(status = AttachmentStatus.FAILED))
            return
        }

        val localFileName = "attach_$id"
        withContext(Dispatchers.IO) {
            encryptedFileStore.openOutputStream(localFileName).use { it.write(assembled) }
        }

        val thumbnailFileName = if (entity.kind == AttachmentKind.IMAGE) {
            generateAndStoreThumbnail(id, assembled)
        } else null

        attachmentDao.upsert(
            entity.copy(status = AttachmentStatus.COMPLETE, localFileName = localFileName, thumbnailFileName = thumbnailFileName)
        )
    }

    private suspend fun generateAndStoreThumbnail(attachmentId: String, imageBytes: ByteArray): String? =
        withContext(Dispatchers.Default) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return@withContext null
                val maxDim = 256
                val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
                val thumb = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap

                val out = java.io.ByteArrayOutputStream()
                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                val thumbFileName = "thumb_$attachmentId"
                withContext(Dispatchers.IO) {
                    encryptedFileStore.openOutputStream(thumbFileName).use { it.write(out.toByteArray()) }
                }
                thumbFileName
            } catch (e: Exception) {
                null // thumbnail generation is best-effort; a failed thumbnail must never fail the whole attachment
            }
        }

    /** Decrypts and returns the full bytes of a completed attachment or thumbnail, for display/export. */
    suspend fun readDecrypted(fileName: String): ByteArray = withContext(Dispatchers.IO) {
        encryptedFileStore.openInputStream(fileName).use { it.readBytes() }
    }

    private fun readPickedFile(uri: Uri): Triple<ByteArray, String, String>? {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        var fileName = "file"
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
        }

        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        return Triple(bytes, fileName, mimeType)
    }

    private fun kindForMimeType(mimeType: String): AttachmentKind = when {
        mimeType.startsWith("image/") -> AttachmentKind.IMAGE
        mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
        mimeType.startsWith("video/") -> AttachmentKind.VIDEO
        else -> AttachmentKind.FILE
    }

    /**
     * Phase 12 "low storage" handling: refuses to start a transfer that
     * clearly won't fit, rather than writing partway and failing with a
     * confusing IOException mid-transfer. requiredBytes is compared
     * against available space with headroom (2x) since the in-memory
     * buffer, the final encrypted file, AND (for images) a thumbnail
     * may all briefly coexist during assembly.
     */
    private fun hasEnoughFreeSpace(requiredBytes: Long): Boolean {
        return try {
            val stat = android.os.StatFs(context.filesDir.path)
            stat.availableBytes > requiredBytes * 2
        } catch (e: Exception) {
            true // if we can't even check, don't block the transfer on that basis alone
        }
    }
}
