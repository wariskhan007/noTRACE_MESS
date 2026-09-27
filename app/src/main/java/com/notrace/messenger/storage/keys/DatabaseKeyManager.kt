package com.notrace.messenger.storage.keys

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Owns the SQLCipher database passphrase.
 *
 * Design (plan Section 6 "Android Keystore integration", rule #4 "never
 * invent cryptography"):
 *  - The actual DB passphrase is 256 bits of SecureRandom output — not a
 *    user password, not derived from anything guessable.
 *  - It is stored inside EncryptedSharedPreferences, whose master key is
 *    an AES-256-GCM key generated and held in the Android Keystore
 *    (hardware-backed on devices that support it). We never touch raw
 *    Keystore key material directly here; MasterKey/EncryptedSharedPreferences
 *    (Jetpack Security, from Google) is the audited implementation.
 *  - On first launch: generate + store. On every later launch: retrieve
 *    the same value, so SQLCipher can open the existing database file.
 *
 * If this passphrase is ever lost (e.g. EncryptedSharedPreferences wiped
 * without wiping the DB file too), the database becomes unrecoverable.
 * That's intentional — it is the same "no recovery path" behavior locked
 * in for identity keys (Phase 0, decision #14), and it's why
 * StorageWipeManager always deletes both together.
 */
class DatabaseKeyManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Returns the existing passphrase, or generates and persists a new
     * one if none exists yet (first launch). Returned as a CharArray so
     * callers can zero it out after use (SQLCipher's SupportFactory
     * accepts a byte array/CharArray rather than requiring a String,
     * since Strings are immutable and can't be scrubbed from memory).
     */
    @Synchronized
    fun getOrCreatePassphrase(): CharArray {
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return existing.toCharArray()
        }
        val generated = generatePassphrase()
        prefs.edit().putString(KEY_DB_PASSPHRASE, String(generated)).apply()
        return generated
    }

    /**
     * Deletes the stored passphrase. Used only by StorageWipeManager, and
     * only ever together with deleting the actual database file — deleting
     * one without the other either orphans an undecryptable DB file, or
     * leaves a passphrase with nothing to unlock.
     */
    fun deletePassphrase() {
        prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
    }

    private fun generatePassphrase(): CharArray {
        val random = SecureRandom()
        val bytes = ByteArray(32) // 256 bits
        random.nextBytes(bytes)
        // Hex-encode so it's safely representable as SharedPreferences
        // String storage without encoding concerns.
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    companion object {
        private const val PREFS_FILE_NAME = "notrace_secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
    }
}
