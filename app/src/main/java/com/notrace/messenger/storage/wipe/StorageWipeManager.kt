package com.notrace.messenger.storage.wipe

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.notrace.messenger.call.service.CallForegroundService
import com.notrace.messenger.storage.db.NoTraceDatabase
import com.notrace.messenger.storage.files.EncryptedFileStore
import com.notrace.messenger.storage.keys.DatabaseKeyManager

/**
 * Storage-layer primitive for destroying all of NoTrace's own data.
 *
 * Implements the plan's destruction sequence (Section 8) precisely, in
 * order - cryptographic erasure, not physical overwrite, per the
 * privacy claims locked in Phase 0:
 *   1. Destroy the DB encryption key (the passphrase - step 3 below).
 *   2-4. Delete encrypted message records / attachments / thumbnails
 *        (all live in the DB file and the EncryptedFileStore
 *        directory - steps 2 and 4 below cover all of these together).
 *   5. Delete temporary files (cache - step 5).
 *   6. Clear app-owned caches (same step).
 *   7. Remove app-owned notification content (step 6 below).
 *   8. Remove session state (in the DB file - step 2).
 *   9. Clear app-owned metadata (in the DB file - step 2).
 *  10. Close database connections (step 1).
 *  11. Remove key references from memory where practical - this one
 *      genuinely cannot be done by this class alone: an already-loaded
 *      IdentityKeyPair/passphrase char array may still be referenced by
 *      other long-lived singletons (AppContainer) for the rest of this
 *      process's life even after this method returns. The caller
 *      (IdentityRepository.deleteAccountAndWipeAllData, driven from
 *      Settings' "Delete account") is responsible for actually
 *      restarting the app process afterward so nothing is left
 *      resident in memory - see that method's own doc for why.
 *  12. Verification that destroyed data is unreachable - see
 *      StorageWipeManagerTest (Phase 11 "destruction tests").
 *
 * Scope: strictly this app's own app-private storage. Never touches
 * external/shared storage, other apps' data, or anything the user
 * explicitly exported — matching the Phase 0 self-destruct boundary.
 */
class StorageWipeManager(private val context: Context) {

    fun wipeAll() {
        // 1. Close DB connection if open.
        NoTraceDatabase.closeIfOpen()

        // 2. Delete DB file + WAL/SHM siblings - this alone removes
        // every table's contents: messages, attachments metadata,
        // sessions, group sender keys, contacts, everything.
        val dbFile = NoTraceDatabase.databaseFile(context)
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        // 3. Delete the passphrase. Order matters: only delete this AFTER
        // the DB file is gone, so a crash between these two steps never
        // leaves an existing DB file with no way to open it again outside
        // of a fresh wipe (fails safe toward "unreadable", not toward a
        // half-broken database).
        DatabaseKeyManager(context).deletePassphrase()

        // 4. Delete encrypted attachments and thumbnails.
        EncryptedFileStore(context).deleteAll()

        // 5. Clear cache (includes any in-flight voice-note temp files - Phase 8's VoiceNoteRecorder writes there).
        context.cacheDir.deleteRecursively()

        // 6. Remove app-owned notification content (Phase 11: destruction
        // sequence step 7) - cancel anything NoTrace itself posted, and
        // stop the call foreground service if one happens to be running
        // (a wipe mid-call is an edge case, but a lingering "Ongoing
        // call" notification referencing already-destroyed state would
        // be exactly the kind of leftover this step exists to prevent).
        NotificationManagerCompat.from(context).cancelAll()
        context.stopService(Intent(context, CallForegroundService::class.java))
    }
}

private typealias File = java.io.File
