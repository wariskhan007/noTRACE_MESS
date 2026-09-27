package com.notrace.messenger.maintenance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notrace.messenger.attachment.data.AttachmentStatus
import com.notrace.messenger.storage.db.NoTraceDatabase

private const val STALE_THRESHOLD_MS = 24L * 60 * 60 * 1000 // 24 hours

/**
 * Phase 12's one real WorkManager job (see build.gradle.kts's comment
 * on why only this, not the message-expiry sweep, uses WorkManager).
 *
 * Finds attachment transfers stuck in SENDING/RECEIVING that are more
 * than 24 hours old - these will never complete on their own (the
 * chunked-transfer protocol, Phase 7, has no resume/retry mechanism,
 * so a peer going permanently offline mid-transfer just leaves the
 * record stuck forever otherwise) - and marks them FAILED rather than
 * either leaving them stuck indefinitely OR silently deleting them.
 * Marking as FAILED (not deleting) matters: the user should be able to
 * see in the conversation that a transfer didn't complete, not have it
 * quietly vanish (plan rule: no silent placeholders/fake success).
 *
 * Does NOT touch AttachmentTransferManager's in-process receive
 * buffers - those are already gone the moment the process dies, this
 * only cleans up the DB-level record left behind.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = NoTraceDatabase.getInstance(applicationContext)
        val cutoff = System.currentTimeMillis() - STALE_THRESHOLD_MS

        val stale = db.attachmentDao().getIncomplete().filter { it.createdAtEpochMillis < cutoff }
        stale.forEach { attachment ->
            db.attachmentDao().upsert(attachment.copy(status = AttachmentStatus.FAILED))
        }

        return Result.success()
    }
}
