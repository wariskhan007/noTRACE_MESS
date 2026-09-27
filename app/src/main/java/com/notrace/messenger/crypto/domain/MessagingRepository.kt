package com.notrace.messenger.crypto.domain

import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.crypto.data.SessionDao
import com.notrace.messenger.identity.data.ContactDao
import com.notrace.messenger.identity.data.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

sealed class SendResult {
    data object Success : SendResult()
    data object NoSession : SendResult()
    data class Failed(val reason: String) : SendResult()
}

sealed class EstablishSessionResult {
    data object Success : EstablishSessionResult()
    data class Failed(val reason: String) : EstablishSessionResult()
}

sealed class ReceiveResult {
    data class Success(val plaintext: String) : ReceiveResult()
    /** Message decrypted successfully, but the contact's identity key changed since last seen (Section 10). */
    data class SuccessWithIdentityChange(val plaintext: String) : ReceiveResult()
    /**
     * Decrypted successfully, but the plaintext wasn't a chat-text
     * envelope (Phase 7: attachment-meta/attachment-chunk). NOT
     * inserted into the messages table - the caller (P2PSessionCoordinator)
     * routes `json` to AttachmentTransferManager instead. Kept as part
     * of ReceiveResult rather than a second decrypt path because a
     * ciphertext must only ever be decrypted once - libsignal's replay
     * protection and ratchet state depend on that.
     */
    data class ControlEnvelope(val kind: String, val json: org.json.JSONObject) : ReceiveResult()
    data object Duplicate : ReceiveResult()
    data class Rejected(val reason: String) : ReceiveResult()
}

/**
 * Ties together identity keys, the protocol store, session
 * establishment, and encrypt/decrypt into one API the UI layer calls.
 *
 * This repository stays transport-agnostic on purpose:
 * encryptAndQueue() persists the ciphertext-derived plaintext row
 * locally as PENDING and returns it plus its row id, and
 * receiveAndDecrypt() just takes ciphertext bytes from wherever they
 * came from. Phase 4 fed those manually (copy-paste); Phase 5's
 * P2PSessionCoordinator now feeds them automatically over a WebRTC
 * data channel when the peer is online, falling back to manual paste
 * when not - either way, everything in this class - sessions, ratchet
 * state, replay rejection, identity-change detection - stays identical
 * bytes eventually get from one device to another.
 */
class MessagingRepository(
    private val cryptoIdentityManager: CryptoIdentityManager,
    private val protocolStore: NoTraceProtocolStore,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao
) {
    fun observeConversation(contactRandomId: String): Flow<List<MessageEntity>> =
        messageDao.observeConversation(contactRandomId)

    /** Builds this device's shareable key bundle (Section: "share your ID" flow, extended with real key material). */
    suspend fun exportOwnBundle(): String = withContext(Dispatchers.Default) {
        val identityKeyPair = cryptoIdentityManager.getOrCreateIdentityKeyPair()
        cryptoIdentityManager.ensurePreKeysAvailable(identityKeyPair)
        val registrationId = cryptoIdentityManager.getLocalRegistrationId()
        val signedPreKey = cryptoIdentityManager.currentSignedPreKey()
        val oneTimePreKey = cryptoIdentityManager.takeOneOneTimePreKey()

        BundleCodec.encode(
            registrationId = registrationId,
            oneTimePreKey = oneTimePreKey,
            signedPreKey = signedPreKey,
            identityKey = identityKeyPair.publicKey
        )
    }

    /** Consumes a contact's exported bundle to start (or restart) a session with them. */
    suspend fun establishSession(contactRandomId: String, encodedBundle: String): EstablishSessionResult =
        withContext(Dispatchers.Default) {
            try {
                val bundle: PreKeyBundle = BundleCodec.decode(encodedBundle)
                val address = SignalProtocolAddress(contactRandomId, 1)
                val builder = SessionBuilder(protocolStore, address)
                builder.process(bundle)
                EstablishSessionResult.Success
            } catch (e: InvalidKeyException) {
                EstablishSessionResult.Failed("That bundle isn't valid: ${e.message}")
            } catch (e: UntrustedIdentityException) {
                EstablishSessionResult.Failed("Identity key mismatch: ${e.message}")
            } catch (e: Exception) {
                EstablishSessionResult.Failed("Couldn't establish session: ${e.message}")
            }
        }

    suspend fun hasSession(contactRandomId: String): Boolean =
        sessionDao.get(contactRandomId) != null

    /**
     * Encrypts a plaintext wire envelope and stores it locally as
     * PENDING. As of Phase 7, "plaintext" here is always a JSON string
     * of shape {"kind": "text"|"attachment-meta"|"attachment-chunk", ...}
     * - callers build that envelope (ChatViewModel for text,
     * AttachmentTransferManager for attachment kinds) before calling
     * this; this method itself doesn't care what's inside, it just
     * encrypts whatever string it's given. Returns the inserted row's
     * id alongside the result so a transport layer (P2PSessionCoordinator)
     * can flip it to SENT once it's actually handed off.
     */
    suspend fun encryptAndQueue(contactRandomId: String, plaintext: String): Triple<SendResult, String?, Long?> =
        withContext(Dispatchers.Default) {
            if (!hasSession(contactRandomId)) return@withContext Triple(SendResult.NoSession, null, null)

            try {
                val address = SignalProtocolAddress(contactRandomId, 1)
                val cipher = SessionCipher(protocolStore, address)
                val ciphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
                val encoded = android.util.Base64.encodeToString(
                    ciphertextMessage.serialize(), android.util.Base64.NO_WRAP
                )

                // Only log an actual chat message row for text envelopes -
                // attachment-meta/attachment-chunk envelopes are tracked
                // in their own AttachmentEntity (Phase 7), not the message
                // list, or every chunk would show up as a garbage "message".
                val displayBody = extractTextBodyOrNull(plaintext)
                val rowId = if (displayBody != null) {
                    val expiresAt = expiryTimestampFor(contactRandomId)
                    messageDao.insert(
                        MessageEntity(
                            contactRandomId = contactRandomId,
                            direction = MessageDirection.OUTGOING,
                            body = displayBody,
                            sentOrReceivedAtEpochMillis = System.currentTimeMillis(),
                            deliveryState = DeliveryState.PENDING,
                            expiresAtEpochMillis = expiresAt
                        )
                    )
                } else null
                Triple(SendResult.Success, encoded, rowId)
            } catch (e: Exception) {
                Triple(SendResult.Failed(e.message ?: "Unknown encryption error"), null, null)
            }
        }

    /**
     * Returns the chat text to display/log for a locally-composed
     * envelope, or null if this envelope isn't a chat-text message
     * (i.e. it's a Phase 7 attachment-meta/attachment-chunk envelope or
     * a Phase 10 group-* wire-protocol envelope, neither of which gets
     * a 1:1 message-list row of its own - GroupCoordinator logs its own
     * group-conversation row separately, keyed by groupId). A plaintext
     * that isn't JSON at all is treated as a legacy plain-text message
     * and returned as-is - keeps this backward compatible with anything
     * encrypted before Phase 7 introduced envelopes.
     */
    private fun extractTextBodyOrNull(plaintext: String): String? {
        return try {
            val json = org.json.JSONObject(plaintext)
            when (json.optString("kind")) {
                "text" -> json.optString("body")
                "attachment-meta", "attachment-chunk" -> null
                "group-invite", "group-sender-key-distribution", "group-text",
                "group-member-added", "group-member-removed", "group-member-left", "group-dissolved" -> null
                "timer-update", "group-timer-update" -> null
                else -> plaintext // unrecognized kind - fail safe by showing it rather than silently dropping
            }
        } catch (e: Exception) {
            plaintext // not JSON at all - legacy/plain text
        }
    }

    suspend fun markDelivered(messageId: Long, state: DeliveryState) {
        messageDao.updateDeliveryState(messageId, state)
    }

    /** Local-only update; sending the corresponding "timer-update" envelope to the contact is the transport layer's job (P2PSessionCoordinator). */
    suspend fun setDisappearingMessageSeconds(contactRandomId: String, seconds: Long?) {
        contactDao.setDisappearingMessageSeconds(contactRandomId, seconds)
    }

    /**
     * Decrypts ciphertext that arrived from a contact (however it got
     * here - Phase 5 will make this automatic). isPreKeyMessage tells
     * us which SignalMessage subtype to parse, since the wire format
     * differs for a session-establishing first message vs. a normal
     * ratchet message.
     */
    suspend fun receiveAndDecrypt(
        contactRandomId: String,
        encodedCiphertext: String,
        isPreKeyMessage: Boolean
    ): ReceiveResult = withContext(Dispatchers.Default) {
        val bytes = android.util.Base64.decode(encodedCiphertext, android.util.Base64.NO_WRAP)
        val address = SignalProtocolAddress(contactRandomId, 1)
        val cipher = SessionCipher(protocolStore, address)

        val identityBefore = protocolStore.getIdentity(address)

        val plaintextBytes: ByteArray = try {
            if (isPreKeyMessage) {
                cipher.decrypt(PreKeySignalMessage(bytes))
            } else {
                cipher.decrypt(SignalMessage(bytes))
            }
        } catch (e: DuplicateMessageException) {
            return@withContext ReceiveResult.Duplicate
        } catch (e: InvalidMessageException) {
            return@withContext ReceiveResult.Rejected("Invalid or tampered ciphertext")
        } catch (e: LegacyMessageException) {
            return@withContext ReceiveResult.Rejected("Message uses an unsupported old protocol version")
        } catch (e: NoSessionException) {
            return@withContext ReceiveResult.Rejected("No session with this contact yet")
        } catch (e: UntrustedIdentityException) {
            return@withContext ReceiveResult.Rejected("Untrusted identity key")
        } catch (e: Exception) {
            return@withContext ReceiveResult.Rejected(e.message ?: "Unknown decryption error")
        }

        val raw = String(plaintextBytes, Charsets.UTF_8)
        val identityAfter = protocolStore.getIdentity(address)
        val identityChanged = identityBefore != null && identityAfter != null &&
            !identityBefore.serialize().contentEquals(identityAfter.serialize())

        if (identityChanged) {
            // Section 10: downgrade a previously-verified contact rather
            // than silently continuing to show them as verified against a
            // key that's no longer the one actually in use. This applies
            // regardless of envelope kind - it's a property of the
            // session/identity, not of what was said.
            contactDao.setVerificationStatus(contactRandomId, VerificationStatus.NEEDS_REVERIFICATION)
        }

        val envelopeKind = try { org.json.JSONObject(raw).optString("kind") } catch (e: Exception) { "" }
        when (envelopeKind) {
            "attachment-meta", "attachment-chunk",
            "group-invite", "group-sender-key-distribution", "group-text",
            "group-member-added", "group-member-removed", "group-member-left", "group-dissolved",
            "timer-update", "group-timer-update" -> {
                // Not a 1:1 chat message - no MessageEntity row here.
                // Routed via P2PSessionCoordinator's ControlEnvelope
                // handling to AttachmentTransferManager (Phase 7) or
                // GroupCoordinator (Phase 10) respectively.
                val json = org.json.JSONObject(raw)
                return@withContext ReceiveResult.ControlEnvelope(envelopeKind, json)
            }
            else -> Unit // "text", or unrecognized/legacy plain text - fall through to logging it below
        }

        val displayBody = try {
            val json = org.json.JSONObject(raw)
            if (json.optString("kind") == "text") json.optString("body") else raw
        } catch (e: Exception) {
            raw // not JSON - legacy/plain text, log as-is
        }

        messageDao.insert(
            MessageEntity(
                contactRandomId = contactRandomId,
                direction = MessageDirection.INCOMING,
                body = displayBody,
                sentOrReceivedAtEpochMillis = System.currentTimeMillis(),
                deliveryState = DeliveryState.DELIVERED,
                expiresAtEpochMillis = expiryTimestampFor(contactRandomId)
            )
        )

        if (identityChanged) {
            ReceiveResult.SuccessWithIdentityChange(displayBody)
        } else {
            ReceiveResult.Success(displayBody)
        }
    }

    /**
     * Phase 11: the timer start point is DELIVERY (see
     * DisappearingTimerOption's class doc for why) - computed once, at
     * insert time, from whatever this contact's CURRENT timer setting
     * is. A later change to the setting never retroactively changes an
     * already-computed expiry on existing rows, matching how every
     * other messenger's disappearing-timer setting works (it affects
     * new messages going forward, not history).
     */
    private suspend fun expiryTimestampFor(contactRandomId: String): Long? {
        val seconds = contactDao.get(contactRandomId)?.disappearingMessageSeconds ?: return null
        return System.currentTimeMillis() + seconds * 1000
    }
}
