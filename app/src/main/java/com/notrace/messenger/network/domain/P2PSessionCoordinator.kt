package com.notrace.messenger.network.domain

import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.domain.EstablishSessionResult
import com.notrace.messenger.crypto.domain.MessagingRepository
import com.notrace.messenger.crypto.domain.ReceiveResult
import com.notrace.messenger.crypto.domain.SendResult
import com.notrace.messenger.network.signaling.IncomingSignal
import com.notrace.messenger.network.signaling.SignalingClient
import com.notrace.messenger.network.signaling.SignalingConnectionState
import com.notrace.messenger.network.webrtc.P2PConnectionManager
import com.notrace.messenger.network.webrtc.P2PConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/** A non-text envelope decrypted from this contact (Phase 7: attachment-meta/attachment-chunk), for AttachmentTransferManager to consume. */
data class ControlEnvelope(val contactRandomId: String, val kind: String, val json: JSONObject)

/**
 * Automates what Phase 4's ChatScreen made the USER do by hand: bundle
 * exchange, WebRTC offer/answer/ICE exchange over signaling, and
 * routing ciphertext over the resulting data channel instead of
 * copy-paste. One coordinator instance per contact, created on demand
 * when a chat is opened, torn down when it's closed.
 *
 * Phase 6 added a fallback when there's no live data channel: the same
 * ciphertext goes to the signaling server as a {kind:"relayed-message"}
 * payload instead, which the server holds (temporary ciphertext relay,
 * up to 7 days or until acked) and delivers automatically next time
 * the recipient connects.
 *
 * Phase 7 generalizes what gets sent: every plaintext handed to
 * MessagingRepository is now a small JSON envelope
 * ({"kind":"text",...} for chat messages, {"kind":"attachment-meta"/
 * "attachment-chunk",...} for file transfer - see AttachmentTransferManager).
 * This class doesn't care which - sendEnvelope()/the receive path both
 * work identically regardless of what's inside, and MessagingRepository
 * only logs an actual chat-message row for "text" envelopes.
 *
 * Wire protocol over the signaling `payload` field (opaque to the
 * server, meaningful only here):
 *   {kind:"bundle-request"}
 *   {kind:"bundle", data: <base64 PreKeyBundle, BundleCodec format>}
 *   {kind:"sdp-offer", sdp, type}
 *   {kind:"sdp-answer", sdp, type}
 *   {kind:"ice-candidate", sdpMid, sdpMLineIndex, candidate}
 *   {kind:"relayed-message", ciphertext, isPreKeyMessage}  -- Phase 6,
 *     only sent when there's no live data channel to this contact. The
 *     ciphertext's OWN inner plaintext (once decrypted) carries its own
 *     independent "kind" (text/attachment-meta/attachment-chunk) - two
 *     different, unrelated uses of the word "kind" at two different
 *     protocol layers.
 *
 * This class only starts automatic P2P setup for a contact when the
 * user actively opens that chat - it doesn't proactively connect to
 * every known contact in the background (plan Section 12 performance;
 * also keeps the signaling server's connected-clients set meaningfully
 * small and short-lived).
 */
class P2PSessionCoordinator(
    private val signalingClient: SignalingClient,
    private val p2pFactory: (contactRandomId: String) -> P2PConnectionManager,
    private val messagingRepository: MessagingRepository,
    private val scope: CoroutineScope
) {
    private val connections = mutableMapOf<String, P2PConnectionManager>()

    /** True once we've sent our first (PreKey) message in this app-process's session with this contact. */
    private val hasSentFirstMessage = mutableMapOf<String, Boolean>()

    /**
     * Envelopes waiting for the signaling connection to become READY so
     * they can go out via the Phase 6 relay fallback. An envelope only
     * ends up here if there was no live data channel AND signaling
     * wasn't ready yet at send time; flushed automatically once READY.
     * Known limitation: purely in-memory, so a process death loses
     * anything still queued here (not yet handed to the relay) - a
     * more durable retry belongs in Phase 12 (performance/reliability),
     * not this phase.
     */
    private val pendingRelaySends = mutableMapOf<String, MutableList<PendingSend>>()

    private val _peerStatus = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    val peerStatus: StateFlow<Map<String, PeerStatus>> = _peerStatus.asStateFlow()

    /** Non-text envelopes (Phase 7 attachment protocol) for AttachmentTransferManager to subscribe to. */
    private val _controlEnvelopes = MutableSharedFlow<ControlEnvelope>(extraBufferCapacity = 64)
    val controlEnvelopes: SharedFlow<ControlEnvelope> = _controlEnvelopes.asSharedFlow()

    init {
        signalingClient.incomingSignals.onEach { handleIncomingSignal(it) }.launchIn(scope)
        signalingClient.peerOfflineEvents.onEach { contactId ->
            updateStatus(contactId, PeerStatus.OFFLINE)
        }.launchIn(scope)
        signalingClient.connectionState.onEach { state ->
            if (state == SignalingConnectionState.READY) flushPendingRelaySends()
        }.launchIn(scope)
    }

    /** Call when the user opens a chat with this contact. Connects signaling if needed and starts the handshake. */
    fun startChat(contactRandomId: String) {
        if (signalingClient.connectionState.value != SignalingConnectionState.READY) {
            signalingClient.connect()
        }
        scope.launch {
            if (!messagingRepository.hasSession(contactRandomId)) {
                updateStatus(contactRandomId, PeerStatus.REQUESTING_BUNDLE)
                signalingClient.sendSignal(contactRandomId, JSONObject().put("kind", "bundle-request"))
            } else {
                startWebRtcAsInitiator(contactRandomId)
            }
        }
    }

    fun endChat(contactRandomId: String) {
        connections.remove(contactRandomId)?.close()
        updateStatus(contactRandomId, PeerStatus.IDLE)
    }

    /** Sends a chat text message: wraps it as a {"kind":"text"} envelope and transmits it. */
    fun sendMessage(contactRandomId: String, plaintext: String) {
        val envelope = JSONObject().put("kind", "text").put("body", plaintext)
        scope.launch { sendEnvelope(contactRandomId, envelope.toString()) }
    }

    /**
     * Low-level send used by both sendMessage (text) and
     * AttachmentTransferManager (attachment-meta/attachment-chunk
     * envelopes). Encrypts, then tries the live data channel first,
     * falling back to the Phase 6 relay queue when there's no live
     * connection. Whether this creates a visible chat-message row is
     * entirely MessagingRepository's call, based on the envelope's own
     * "kind" field - this function doesn't need to know or care.
     */
    suspend fun sendEnvelope(contactRandomId: String, envelopePlaintext: String): Boolean {
        val (result, ciphertext, rowId) = messagingRepository.encryptAndQueue(contactRandomId, envelopePlaintext)
        if (result != SendResult.Success || ciphertext == null) return false

        val isFirst = !(hasSentFirstMessage[contactRandomId] ?: false)
        val wireEnvelope = JSONObject().apply {
            put("ciphertext", ciphertext)
            put("isPreKeyMessage", isFirst)
        }

        val sentLive = connections[contactRandomId]?.sendMessage(wireEnvelope.toString()) ?: false
        if (sentLive) {
            hasSentFirstMessage[contactRandomId] = true
            rowId?.let { messagingRepository.markDelivered(it, DeliveryState.SENT) }
            return true
        }

        sendViaRelayOrQueue(contactRandomId, rowId, isFirst, wireEnvelope)
        return true // handed off (live or queued-for-relay) - not a hard failure either way
    }

    private fun sendViaRelayOrQueue(contactRandomId: String, rowId: Long?, isFirst: Boolean, envelope: JSONObject) {
        if (signalingClient.connectionState.value == SignalingConnectionState.READY) {
            hasSentFirstMessage[contactRandomId] = true
            signalingClient.sendSignal(
                contactRandomId,
                JSONObject().apply {
                    put("kind", "relayed-message")
                    put("ciphertext", envelope.getString("ciphertext"))
                    put("isPreKeyMessage", envelope.getBoolean("isPreKeyMessage"))
                }
            )
            rowId?.let { id -> scope.launch { messagingRepository.markDelivered(id, DeliveryState.SENT) } }
        } else {
            pendingRelaySends.getOrPut(contactRandomId) { mutableListOf() }
                .add(PendingSend(rowId, isFirst, envelope))
            signalingClient.connect()
        }
    }

    private fun flushPendingRelaySends() {
        val snapshot = pendingRelaySends.toMap()
        pendingRelaySends.clear()
        for ((contactId, sends) in snapshot) {
            for (pending in sends) {
                sendViaRelayOrQueue(contactId, pending.rowId, pending.isFirst, pending.envelope)
            }
        }
    }

    private fun handleIncomingSignal(signal: IncomingSignal) {
        val contactId = signal.from
        when (signal.payload.optString("kind")) {
            "bundle-request" -> scope.launch {
                val myBundle = messagingRepository.exportOwnBundle()
                signalingClient.sendSignal(
                    contactId,
                    JSONObject().put("kind", "bundle").put("data", myBundle)
                )
            }
            "bundle" -> scope.launch {
                val data = signal.payload.getString("data")
                val result = messagingRepository.establishSession(contactId, data)
                if (result is EstablishSessionResult.Success) {
                    startWebRtcAsInitiator(contactId)
                } else {
                    updateStatus(contactId, PeerStatus.FAILED)
                }
            }
            "sdp-offer" -> {
                val manager = connections.getOrPut(contactId) { p2pFactory(contactId).also { observe(contactId, it) } }
                val offer = SessionDescription(SessionDescription.Type.OFFER, signal.payload.getString("sdp"))
                manager.acceptOfferAsResponder(offer) { answer ->
                    signalingClient.sendSignal(
                        contactId,
                        JSONObject().put("kind", "sdp-answer").put("sdp", answer.description).put("type", answer.type.canonicalForm())
                    )
                }
                updateStatus(contactId, PeerStatus.CONNECTING)
            }
            "sdp-answer" -> {
                val answer = SessionDescription(SessionDescription.Type.ANSWER, signal.payload.getString("sdp"))
                connections[contactId]?.onAnswerReceived(answer)
            }
            "ice-candidate" -> {
                val candidate = IceCandidate(
                    signal.payload.getString("sdpMid"),
                    signal.payload.getInt("sdpMLineIndex"),
                    signal.payload.getString("candidate")
                )
                connections[contactId]?.onRemoteIceCandidateReceived(candidate)
            }
            "relayed-message" -> scope.launch {
                val ciphertext = signal.payload.optString("ciphertext")
                val isPreKeyMessage = signal.payload.optBoolean("isPreKeyMessage")
                val result = messagingRepository.receiveAndDecrypt(contactId, ciphertext, isPreKeyMessage)
                handleDecryptResult(contactId, result)
                // Ack regardless of outcome (Phase 6: this is a "temporary
                // relay, not exactly-once delivery" - a permanently
                // undecryptable item would just sit there forever
                // otherwise, and libsignal's own replay protection is the
                // real backstop against acting on a redelivered duplicate).
                signal.relayMessageId?.let { signalingClient.sendAck(it) }
            }
        }
    }

    private fun startWebRtcAsInitiator(contactId: String) {
        updateStatus(contactId, PeerStatus.CONNECTING)
        val manager = connections.getOrPut(contactId) { p2pFactory(contactId).also { observe(contactId, it) } }
        manager.createOfferAsInitiator { offer ->
            signalingClient.sendSignal(
                contactId,
                JSONObject().put("kind", "sdp-offer").put("sdp", offer.description).put("type", offer.type.canonicalForm())
            )
        }
    }

    private fun observe(contactId: String, manager: P2PConnectionManager) {
        manager.localIceCandidates.onEach { candidate ->
            signalingClient.sendSignal(
                contactId,
                JSONObject()
                    .put("kind", "ice-candidate")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
            )
        }.launchIn(scope)

        manager.connectionState.onEach { state ->
            updateStatus(
                contactId,
                when (state) {
                    P2PConnectionState.CONNECTED -> PeerStatus.CONNECTED
                    P2PConnectionState.FAILED -> PeerStatus.FAILED
                    P2PConnectionState.CLOSED -> PeerStatus.IDLE
                    else -> PeerStatus.CONNECTING
                }
            )
        }.launchIn(scope)

        manager.incomingMessages.onEach { raw ->
            val envelope = try { JSONObject(raw) } catch (e: Exception) { return@onEach }
            val ciphertext = envelope.optString("ciphertext")
            val isPreKeyMessage = envelope.optBoolean("isPreKeyMessage")
            val result = messagingRepository.receiveAndDecrypt(contactId, ciphertext, isPreKeyMessage)
            handleDecryptResult(contactId, result)
        }.launchIn(scope)
    }

    /** Shared handling for a decrypt outcome, regardless of which transport (live data channel or relay) carried the ciphertext. */
    private fun handleDecryptResult(contactId: String, result: ReceiveResult) {
        when (result) {
            is ReceiveResult.ControlEnvelope -> {
                if (result.kind == "timer-update") {
                    // Phase 11: handled directly here rather than emitted to
                    // controlEnvelopes - simple enough not to need its own
                    // subscriber class, unlike attachments/groups.
                    val seconds = if (result.json.isNull("seconds")) null else result.json.optLong("seconds")
                    scope.launch { messagingRepository.setDisappearingMessageSeconds(contactId, seconds) }
                } else {
                    _controlEnvelopes.tryEmit(ControlEnvelope(contactId, result.kind, result.json))
                }
            }
            is ReceiveResult.Rejected -> updateStatus(contactId, PeerStatus.FAILED)
            else -> Unit // Success/SuccessWithIdentityChange already logged by MessagingRepository; Duplicate is a no-op.
        }
    }

    /** Updates the local setting and notifies the contact so both sides apply the same duration to new messages going forward. */
    fun setDisappearingTimer(contactRandomId: String, seconds: Long?) {
        scope.launch {
            messagingRepository.setDisappearingMessageSeconds(contactRandomId, seconds)
            val envelope = org.json.JSONObject().put("kind", "timer-update")
            if (seconds != null) envelope.put("seconds", seconds) else envelope.put("seconds", org.json.JSONObject.NULL)
            sendEnvelope(contactRandomId, envelope.toString())
        }
    }

    private fun updateStatus(contactId: String, status: PeerStatus) {
        _peerStatus.value = _peerStatus.value.toMutableMap().apply { put(contactId, status) }
    }

    private data class PendingSend(val rowId: Long?, val isFirst: Boolean, val envelope: JSONObject)
}

enum class PeerStatus { IDLE, REQUESTING_BUNDLE, CONNECTING, CONNECTED, OFFLINE, FAILED }
