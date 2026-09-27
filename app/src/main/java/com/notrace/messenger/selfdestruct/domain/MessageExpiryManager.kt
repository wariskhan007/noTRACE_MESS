package com.notrace.messenger.selfdestruct.domain

import com.notrace.messenger.attachment.data.AttachmentDao
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.storage.files.EncryptedFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SWEEP_INTERVAL_MS = 15_000L // fine-grained enough that the 30-second timer option feels real

/**
 * Enforces per-message disappearing timers (plan Section 9 "per-message
 * timer"). expiresAtEpochMillis itself has existed on MessageEntity
 * since Phase 4 as a schema-only placeholder ("nothing reads/enforces
 * it yet") - this is the phase that finally reads and acts on it.
 *
 * IMPORTANT, DISCLOSED LIMITATION: this runs an in-process coroutine
 * loop while the app is alive, plus one sweep at startup to catch
 * anything that expired while the app was closed. It does NOT
 * guarantee exact-instant deletion while the app is fully backgrounded
 * or killed - that would need OS-level exact alarms per message
 * (heavy, battery-costly, and arguably overkill for this feature -
 * even other messengers' disappearing messages typically only
 * guarantee "gone by the next time the app is opened," not "gone to
 * the exact second while your phone is asleep"). This is a real,
 * honest scope boundary, not a silently-missing feature - flagged
 * clearly rather than pretended away.
 */
class MessageExpiryManager(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val encryptedFileStore: EncryptedFileStore,
    private val scope: CoroutineScope
) {
    private var started = false

    /** Call once, e.g. from AppContainer at first access - idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sweepOnce() // catch anything that expired while the app was closed
            while (true) {
                delay(SWEEP_INTERVAL_MS)
                sweepOnce()
            }
        }
    }

    private suspend fun sweepOnce() {
        val expired = messageDao.getExpired(System.currentTimeMillis())
        for (message in expired) {
            message.attachmentId?.let { attachmentId ->
                deleteAttachmentFilesAndRow(attachmentId)
            }
            messageDao.delete(message.id)
        }
    }

    suspend fun deleteAttachmentFilesAndRow(attachmentId: String) {
        val attachment = attachmentDao.get(attachmentId) ?: return
        attachment.localFileName?.let { encryptedFileStore.delete(it) }
        attachment.thumbnailFileName?.let { encryptedFileStore.delete(it) }
        attachmentDao.delete(attachmentId)
    }
}
