package com.notrace.messenger.identity.domain

import com.notrace.messenger.identity.data.SelfIdentityDao
import com.notrace.messenger.identity.data.SelfIdentityEntity
import com.notrace.messenger.storage.wipe.StorageWipeManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of a username edit attempt (Section 10 "username changes"). */
sealed class UsernameChangeResult {
    data object Success : UsernameChangeResult()
    data object TooLong : UsernameChangeResult()
    data object InvalidCharacters : UsernameChangeResult()
}

private val USERNAME_ALLOWED = Regex("^[a-zA-Z0-9_]*$")
private const val USERNAME_MAX_LENGTH = 32

/**
 * Owns this device's own identity (Section 10: account creation,
 * identity persistence, lost-device handling, account deletion).
 *
 * getOrCreateSelfIdentity() is the ONLY place a randomId is ever
 * generated. Called once on first launch (from IdentitySetupScreen);
 * every call after that returns the same persisted identity untouched.
 */
class IdentityRepository(
    private val dao: SelfIdentityDao,
    private val wipeManager: StorageWipeManager
) {
    fun observeSelfIdentity(): Flow<SelfIdentityEntity?> = dao.observe()

    suspend fun hasIdentity(): Boolean = dao.get() != null

    /**
     * Returns the existing identity, or creates a brand-new one
     * (fresh randomId, identityEpoch = 0) if this is the first launch
     * or the identity was previously deleted/wiped.
     */
    suspend fun getOrCreateSelfIdentity(): SelfIdentityEntity {
        dao.get()?.let { return it }

        val fresh = SelfIdentityEntity(
            randomId = RandomIdGenerator.generate(),
            username = null,
            createdAtEpochMillis = System.currentTimeMillis(),
            identityEpoch = 0
        )
        dao.upsert(fresh)
        return fresh
    }

    /**
     * Changes only the display username. randomId and identityEpoch are
     * untouched — existing contacts, verification state, and (from
     * Phase 4 on) crypto sessions are completely unaffected by a
     * username change, by design (Section 10).
     */
    suspend fun setUsername(newUsername: String?): UsernameChangeResult {
        val trimmed = newUsername?.trim()?.takeIf { it.isNotEmpty() }

        if (trimmed != null) {
            if (trimmed.length > USERNAME_MAX_LENGTH) return UsernameChangeResult.TooLong
            if (!USERNAME_ALLOWED.matches(trimmed)) return UsernameChangeResult.InvalidCharacters
        }

        val current = dao.get() ?: return UsernameChangeResult.InvalidCharacters
        dao.upsert(current.copy(username = trimmed))
        return UsernameChangeResult.Success
    }

    /**
     * Full local account deletion (Section 10 "account recovery,
     * lost-device handling"; Section 11 "provide account deletion
     * instructions"). There is no server-side account to delete in this
     * P2P model — deletion means erasing everything on THIS device.
     *
     * This wipes the entire encrypted database (identity, contacts, and
     * anything later phases add), the DB passphrase, encrypted files,
     * and cache, via StorageWipeManager — the same primitive Phase 11's
     * panic wipe will use. After this call, the app behaves exactly
     * like a fresh install: the next getOrCreateSelfIdentity() call
     * generates a brand-new, unrelated randomId. This is intentionally
     * irreversible and is disclosed to the user before they confirm it
     * (see IdentitySetupScreen's onboarding copy and the delete-account
     * confirmation in SettingsScreen).
     */
    fun deleteAccountAndWipeAllData() {
        wipeManager.wipeAll()
    }
}

fun Flow<SelfIdentityEntity?>.displayUsername(): Flow<String?> = map { it?.username }
