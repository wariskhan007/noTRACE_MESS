package com.notrace.messenger.crypto.domain

import com.notrace.messenger.crypto.data.CryptoSelfIdentityDao
import com.notrace.messenger.crypto.data.CryptoSelfIdentityEntity
import com.notrace.messenger.crypto.data.OneTimePreKeyDao
import com.notrace.messenger.crypto.data.OneTimePreKeyEntity
import com.notrace.messenger.crypto.data.SignedPreKeyDao
import com.notrace.messenger.crypto.data.SignedPreKeyEntity
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import kotlin.random.Random

private const val ONE_TIME_PREKEY_COUNT = 100
private const val ONE_TIME_PREKEY_REFILL_THRESHOLD = 20

/**
 * Generates and maintains this device's cryptographic identity: the
 * long-term IdentityKeyPair, registrationId, a signed prekey, and a
 * batch of one-time prekeys (Section 5: "key generation... key
 * rotation"). This is what a remote party's SessionBuilder consumes
 * (as a PreKeyBundle) to start a session with this device.
 *
 * Generation happens once, lazily, the first time it's needed (e.g.
 * when the user first opens a chat or exports their bundle) - not
 * eagerly at every app launch, to keep cold start fast (Phase 1/2
 * performance note).
 */
class CryptoIdentityManager(
    private val cryptoSelfIdentityDao: CryptoSelfIdentityDao,
    private val oneTimePreKeyDao: OneTimePreKeyDao,
    private val signedPreKeyDao: SignedPreKeyDao
) {
    suspend fun getOrCreateIdentityKeyPair(): IdentityKeyPair {
        cryptoSelfIdentityDao.get()?.let { return IdentityKeyPair(it.identityKeyPairBytes) }

        val generated = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        cryptoSelfIdentityDao.upsert(
            CryptoSelfIdentityEntity(
                identityKeyPairBytes = generated.serialize(),
                registrationId = registrationId,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
        return generated
    }

    suspend fun getLocalRegistrationId(): Int =
        cryptoSelfIdentityDao.get()?.registrationId
            ?: run { getOrCreateIdentityKeyPair(); cryptoSelfIdentityDao.get()!!.registrationId }

    /**
     * Ensures there's a current signed prekey and a healthy pool of
     * one-time prekeys, generating new ones if needed. Safe to call
     * repeatedly (e.g. every time the user opens their "share my
     * bundle" screen) - it's a no-op if supplies are already sufficient.
     */
    suspend fun ensurePreKeysAvailable(identityKeyPair: IdentityKeyPair) {
        if (signedPreKeyDao.getAll().isEmpty()) {
            val signedPreKeyId = Random.nextInt(0, Int.MAX_VALUE)
            val keyPair = Curve.generateKeyPair()
            val signature = Curve.calculateSignature(identityKeyPair.privateKey, keyPair.publicKey.serialize())
            val record = SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature)
            signedPreKeyDao.insert(SignedPreKeyEntity(signedPreKeyId, record.serialize(), System.currentTimeMillis()))
        }

        if (oneTimePreKeyDao.countUnissued() < ONE_TIME_PREKEY_REFILL_THRESHOLD) {
            val startId = (oneTimePreKeyDao.maxId() ?: 0) + 1
            repeat(ONE_TIME_PREKEY_COUNT) { offset ->
                val id = startId + offset
                val keyPair = Curve.generateKeyPair()
                val record = PreKeyRecord(id, keyPair)
                oneTimePreKeyDao.insert(OneTimePreKeyEntity(id, record.serialize()))
            }
        }
    }

    /** The current signed prekey record, for building a PreKeyBundle to share. */
    suspend fun currentSignedPreKey(): SignedPreKeyRecord {
        val entity = signedPreKeyDao.getAll().firstOrNull()
            ?: error("No signed prekey - call ensurePreKeysAvailable() first")
        return SignedPreKeyRecord(entity.recordBytes)
    }

    /**
     * Takes one one-time prekey to offer in an exported bundle, and
     * marks it issued so a second export doesn't offer the same one to
     * a different contact.
     *
     * Why not delete it here: in the real Signal architecture, a server
     * hands out each one-time prekey once and never again, but this
     * device still needs to be ABLE to later decrypt the recipient's
     * first PreKeySignalMessage that references this prekey id -
     * libsignal's own SessionCipher.decrypt() call does the actual
     * store.removePreKey() at that point, not before. Deleting it here
     * at export time would make that future decrypt fail with
     * InvalidKeyIdException. NoTrace has no distribution server yet
     * (that's Phase 5), so this manual "export a bundle" flow IS the
     * distribution point and must track "already handed out" itself -
     * via the issued flag - without touching the row libsignal still needs.
     */
    suspend fun takeOneOneTimePreKey(): PreKeyRecord? {
        val entity = oneTimePreKeyDao.getOneUnissued() ?: return null
        oneTimePreKeyDao.markIssued(entity.preKeyId)
        return PreKeyRecord(entity.recordBytes)
    }
}
