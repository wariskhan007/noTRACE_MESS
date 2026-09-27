package com.notrace.messenger.identity.domain

import com.notrace.messenger.identity.data.ContactDao
import com.notrace.messenger.identity.data.ContactEntity
import com.notrace.messenger.identity.data.VerificationStatus
import kotlinx.coroutines.flow.Flow

sealed class AddContactResult {
    data object Success : AddContactResult()
    data object InvalidId : AddContactResult()
    data object CannotAddSelf : AddContactResult()
    data object AlreadyExists : AddContactResult()
}

/**
 * Manages the contact list: add, verify, block, rename, remove
 * (Section 10 "contact discovery, blocking"; Section 11 "identity
 * verification, block/unblock").
 *
 * Contact discovery in V1 is manual/out-of-band: the user is given
 * someone's randomId (read aloud, shown side by side, or pasted) and
 * adds it here. There is no central directory or phone-number lookup
 * (matches the locked identity model — Phase 0 #1).
 */
class ContactRepository(
    private val dao: ContactDao,
    private val selfIdentityRepository: IdentityRepository
) {
    fun observeContacts(): Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun getContact(randomId: String): ContactEntity? = dao.get(randomId)

    suspend fun addContact(rawId: String, nickname: String?): AddContactResult {
        val normalized = RandomIdGenerator.normalize(rawId)
        if (normalized.length != 12) return AddContactResult.InvalidId

        val self = selfIdentityRepository.getOrCreateSelfIdentity()
        if (normalized == self.randomId) return AddContactResult.CannotAddSelf

        if (dao.get(normalized) != null) return AddContactResult.AlreadyExists

        dao.insert(
            ContactEntity(
                randomId = normalized,
                localNickname = nickname?.trim()?.takeIf { it.isNotEmpty() },
                lastKnownUsername = null,
                verificationStatus = VerificationStatus.UNVERIFIED,
                isBlocked = false,
                addedAtEpochMillis = System.currentTimeMillis()
            )
        )
        return AddContactResult.Success
    }

    /**
     * Marks a contact as verified after the user has compared safety
     * numbers/fingerprints out of band. In Phase 3 this is a manual
     * user attestation only — there is no cryptographic fingerprint to
     * compare yet (that lands in Phase 4 once identity keys exist), so
     * the UI is explicit that this is provisional until then.
     */
    suspend fun setVerified(randomId: String, verified: Boolean) {
        dao.setVerificationStatus(
            randomId,
            if (verified) VerificationStatus.VERIFIED else VerificationStatus.UNVERIFIED
        )
    }

    suspend fun setBlocked(randomId: String, blocked: Boolean) {
        dao.setBlocked(randomId, blocked)
    }

    suspend fun renameContact(randomId: String, nickname: String?) {
        dao.setNickname(randomId, nickname?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Local-only removal. Does not notify the other party — there is no server to tell. */
    suspend fun removeContact(randomId: String) {
        dao.delete(randomId)
    }
}
