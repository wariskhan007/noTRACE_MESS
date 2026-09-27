package com.notrace.messenger.storage.files

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted-at-rest file storage helper. Not used by anything yet in
 * Phase 2 (there are no attachments/media until Phase 7) — included now
 * because it's part of the storage layer's foundation and the plan
 * groups "encrypted database, encrypted files, Keystore integration"
 * together in Section 6.
 *
 * Files are written under context.filesDir/attachments/ (app-private,
 * excluded from backup per Phase 1's manifest config) and encrypted with
 * AES256-GCM-HKDF via Jetpack Security's EncryptedFile, using the same
 * kind of Keystore-backed MasterKey as DatabaseKeyManager — but its own
 * key alias, so wiping the DB key doesn't also need to touch file
 * encryption keys and vice versa (keeps the two concerns independent).
 */
class EncryptedFileStore(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, FILE_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").apply { mkdirs() }
    }

    fun openOutputStream(fileName: String): OutputStream {
        val file = File(attachmentsDir, fileName)
        check(!file.exists()) { "Refusing to overwrite existing encrypted file: $fileName" }
        return buildEncryptedFile(file).openFileOutput()
    }

    fun openInputStream(fileName: String): InputStream {
        val file = File(attachmentsDir, fileName)
        return buildEncryptedFile(file).openFileInput()
    }

    fun delete(fileName: String): Boolean = File(attachmentsDir, fileName).delete()

    /** Deletes every file under the attachments dir. Used by StorageWipeManager. */
    fun deleteAll() {
        attachmentsDir.listFiles()?.forEach { it.delete() }
    }

    private fun buildEncryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    companion object {
        private const val FILE_MASTER_KEY_ALIAS = "_notrace_file_master_key_"
    }
}
