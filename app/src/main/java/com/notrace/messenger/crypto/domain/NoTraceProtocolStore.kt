package com.notrace.messenger.crypto.domain

import com.notrace.messenger.crypto.data.OneTimePreKeyDao
import com.notrace.messenger.crypto.data.OneTimePreKeyEntity
import com.notrace.messenger.crypto.data.RemoteIdentityKeyDao
import com.notrace.messenger.crypto.data.RemoteIdentityKeyEntity
import com.notrace.messenger.crypto.data.SessionDao
import com.notrace.messenger.crypto.data.SessionEntity
import com.notrace.messenger.crypto.data.SignedPreKeyDao
import com.notrace.messenger.crypto.data.SignedPreKeyEntity
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * Room-backed implementation of libsignal's SignalProtocolStore family.
 *
 * IMPORTANT / HIGHEST RISK-OF-DRIFT FILE IN THIS PHASE: libsignal-android
 * 0.86.5's exact interface method signatures could not be verified
 * against real compiled sources in this environment (no network access
 * to fetch the artifact). This is written to the well-established,
 * long-stable Signal Protocol Java API shape (org.signal.libsignal.protocol.*,
 * the successor to the older org.whispersystems.libsignal.* package).
 * If the real build reports method-signature mismatches here (missing
 * override, wrong parameter/return type), that is expected as a
 * possibility and should be reported back - the fix is almost always a
 * narrow signature correction in this one file, not a design change.
 *
 * Deliberately implements only the classic four stores (Identity,
 * PreKey, SignedPreKey, Session) - NOT KyberPreKeyStore/PQXDH. Recent
 * libsignal versions support post-quantum prekeys but classic X3DH
 * (Curve25519-only) sessions remain supported for backward
 * compatibility. Adding Kyber support later is additive, not a
 * breaking change to this store or the session model.
 *
 * Methods are synchronous (blocking) because libsignal's Java interfaces
 * are synchronous by design - the suspend-based DAOs underneath are
 * bridged with runBlocking. Callers (MessagingRepository) are expected
 * to invoke SessionBuilder/SessionCipher from a background dispatcher,
 * never the main thread.
 */
class NoTraceProtocolStore(
    private val identityKeyPair: IdentityKeyPair,
    private val localRegistrationId: Int,
    private val oneTimePreKeyDao: OneTimePreKeyDao,
    private val signedPreKeyDao: SignedPreKeyDao,
    private val remoteIdentityKeyDao: RemoteIdentityKeyDao,
    private val sessionDao: SessionDao
) : SignalProtocolStore {

    // --- IdentityKeyStore ---

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean =
        runBlocking {
            val contactRandomId = address.name
            val existing = remoteIdentityKeyDao.get(contactRandomId)
            val changed = existing != null && !existing.identityKeyBytes.contentEquals(identityKey.serialize())
            remoteIdentityKeyDao.upsert(
                RemoteIdentityKeyEntity(
                    contactRandomId = contactRandomId,
                    identityKeyBytes = identityKey.serialize(),
                    firstSeenAtEpochMillis = existing?.firstSeenAtEpochMillis ?: System.currentTimeMillis()
                )
            )
            // Returning true tells libsignal "this identity changed" so the
            // caller (MessagingRepository) can react (Section 10 identity-change
            // warning -> Phase 3's VerificationStatus.NEEDS_REVERIFICATION).
            changed
        }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        // Trust-on-first-use: an unknown contact's first key is trusted
        // automatically (there is no way to verify before any contact
        // exists), matching Phase 3's UNVERIFIED default status. A
        // DIFFERENT key later is flagged as a change by saveIdentity's
        // return value - still allowed to proceed (never silently break
        // messaging), but the caller must surface NEEDS_REVERIFICATION
        // per Section 10 rather than trusting it invisibly.
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = runBlocking {
        remoteIdentityKeyDao.get(address.name)?.let { IdentityKey(it.identityKeyBytes, 0) }
    }

    // --- PreKeyStore (one-time prekeys) ---

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = runBlocking {
        val entity = oneTimePreKeyDao.get(preKeyId) ?: throw InvalidKeyIdException("No such prekey: $preKeyId")
        PreKeyRecord(entity.recordBytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = runBlocking {
        oneTimePreKeyDao.insert(OneTimePreKeyEntity(preKeyId, record.serialize()))
        Unit
    }

    override fun containsPreKey(preKeyId: Int): Boolean = runBlocking {
        oneTimePreKeyDao.get(preKeyId) != null
    }

    override fun removePreKey(preKeyId: Int) = runBlocking {
        // One-time prekeys are deleted once used, by design (forward
        // secrecy of the initial X3DH handshake - Section 5).
        oneTimePreKeyDao.delete(preKeyId)
        Unit
    }

    // --- SignedPreKeyStore ---

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = runBlocking {
        val entity = signedPreKeyDao.get(signedPreKeyId)
            ?: throw InvalidKeyIdException("No such signed prekey: $signedPreKeyId")
        SignedPreKeyRecord(entity.recordBytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = runBlocking {
        signedPreKeyDao.getAll().map { SignedPreKeyRecord(it.recordBytes) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = runBlocking {
        signedPreKeyDao.insert(
            SignedPreKeyEntity(signedPreKeyId, record.serialize(), System.currentTimeMillis())
        )
        Unit
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = runBlocking {
        signedPreKeyDao.get(signedPreKeyId) != null
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) = runBlocking {
        signedPreKeyDao.delete(signedPreKeyId)
        Unit
    }

    // --- SessionStore ---
    // V1 is single-device-per-identity only (Phase 0 decision #8), so
    // deviceId is always assumed to be SignalProtocolAddress.DEFAULT_DEVICE_ID
    // and every method keys purely off address.name (the contact's randomId).

    override fun loadSession(address: SignalProtocolAddress): SessionRecord = runBlocking {
        val entity = sessionDao.get(address.name)
        if (entity != null) SessionRecord(entity.sessionRecordBytes) else SessionRecord()
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): List<SessionRecord> =
        runBlocking {
            addresses.mapNotNull { address ->
                sessionDao.get(address.name)?.let { SessionRecord(it.sessionRecordBytes) }
            }
        }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList() // no multi-device in V1

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = runBlocking {
        sessionDao.upsert(
            SessionEntity(address.name, record.serialize(), System.currentTimeMillis())
        )
        Unit
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = runBlocking {
        sessionDao.get(address.name) != null
    }

    override fun deleteSession(address: SignalProtocolAddress) = runBlocking {
        sessionDao.delete(address.name)
        Unit
    }

    override fun deleteAllSessions(name: String) = runBlocking {
        sessionDao.delete(name)
        Unit
    }
}
