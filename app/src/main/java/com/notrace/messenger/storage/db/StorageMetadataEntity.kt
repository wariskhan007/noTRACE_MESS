package com.notrace.messenger.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table tracking storage-layer metadata that later phases
 * depend on:
 *  - schemaVersion: Room's own migration version (informational mirror
 *    of the @Database version, useful for debugging on-device).
 *  - keyVersion: bumped if/when the encryption scheme for data-at-rest
 *    is ever rotated (plan Section 6 "key-version tracking"), so a
 *    future migration knows whether it needs to re-encrypt anything.
 *  - createdAtEpochMillis: when this device's storage was first
 *    initialized — used later for UI ("member since") and diagnostics,
 *    never transmitted anywhere.
 *
 * This is intentionally the ONLY entity in Phase 2. Contacts, messages,
 * sessions, and conversations are added in Phase 3/4 with their own
 * schema design (and their own migration), not guessed at here.
 */
@Entity(tableName = "storage_metadata")
data class StorageMetadataEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schemaVersion: Int,
    val keyVersion: Int,
    val createdAtEpochMillis: Long
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
