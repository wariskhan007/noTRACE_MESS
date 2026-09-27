package com.notrace.messenger.crypto.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notrace.messenger.attachment.data.AttachmentEntity
import com.notrace.messenger.attachment.domain.AttachmentTransferManager
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.crypto.domain.EstablishSessionResult
import com.notrace.messenger.crypto.domain.MessagingRepository
import com.notrace.messenger.crypto.domain.ReceiveResult
import com.notrace.messenger.crypto.domain.SendResult
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.network.domain.P2PSessionCoordinator
import com.notrace.messenger.network.domain.PeerStatus
import com.notrace.messenger.selfdestruct.domain.ConversationDestructionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * coordinator is null when no signaling server is configured yet
 * (SignalingSettingsStore.getServerUrl() was blank) - in that case
 * this ViewModel behaves exactly as it did in Phase 4: fully manual
 * bundle/ciphertext copy-paste, no automatic anything. When a
 * coordinator IS available, opening the chat automatically attempts
 * bundle exchange + WebRTC connection; the manual controls remain
 * available in the UI as a fallback (e.g. the peer is offline, or the
 * automatic path fails for some reason).
 *
 * attachmentManager is likewise null exactly when coordinator is -
 * attachments (Phase 7) have no manual copy-paste fallback (chunked
 * binary data isn't practical to hand-copy), so file sending is only
 * ever offered when automatic mode is available.
 */
class ChatViewModel(
    private val contactRandomId: String,
    private val repository: MessagingRepository,
    private val coordinator: P2PSessionCoordinator?,
    private val attachmentManager: AttachmentTransferManager?,
    private val contactRepository: ContactRepository,
    private val destructionManager: ConversationDestructionManager
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repository.observeConversation(contactRandomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peerStatus: StateFlow<PeerStatus?> =
        (coordinator?.peerStatus?.map { it[contactRandomId] } ?: kotlinx.coroutines.flow.flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    private val _myBundle = MutableStateFlow<String?>(null)
    val myBundle: StateFlow<String?> = _myBundle.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Latest outgoing ciphertext, for manual copy/share when there's no live P2P connection. */
    private val _lastOutgoingCiphertext = MutableStateFlow<String?>(null)
    val lastOutgoingCiphertext: StateFlow<String?> = _lastOutgoingCiphertext.asStateFlow()

    val automaticModeAvailable: Boolean get() = coordinator != null

    init {
        // hasSession is otherwise a one-shot check (refreshSessionState);
        // when a coordinator is automating bundle exchange in the
        // background, a session can become established without the user
        // taking any action, so re-check on every peer-status change
        // (CONNECTING only ever happens after a session already exists -
        // see P2PSessionCoordinator.startWebRtcAsInitiator). Without
        // this, sendMessage() would keep taking the "No session yet"
        // manual path even after Phase 5/6 already connected everything.
        if (coordinator != null) {
            coordinator.peerStatus
                .map { it[contactRandomId] }
                .let { flow ->
                    viewModelScope.launch {
                        flow.collect { status ->
                            if (status == PeerStatus.CONNECTING || status == PeerStatus.CONNECTED) {
                                _hasSession.value = repository.hasSession(contactRandomId)
                            }
                        }
                    }
                }
        }
    }

    fun refreshSessionState() {
        viewModelScope.launch { _hasSession.value = repository.hasSession(contactRandomId) }
        coordinator?.startChat(contactRandomId)
    }

    fun generateMyBundle() {
        viewModelScope.launch { _myBundle.value = repository.exportOwnBundle() }
    }

    fun establishSession(pastedBundle: String) {
        viewModelScope.launch {
            when (val result = repository.establishSession(contactRandomId, pastedBundle)) {
                EstablishSessionResult.Success -> {
                    _statusMessage.value = "Session established."
                    _hasSession.value = true
                }
                is EstablishSessionResult.Failed -> _statusMessage.value = result.reason
            }
        }
    }

    fun sendMessage(plaintext: String) {
        // Delegate to the coordinator whenever one exists AND a session
        // is already established - it decides live-data-channel vs.
        // Phase 6 relay fallback internally, including while OFFLINE
        // (that's the whole point of the relay). Only fall back to the
        // fully manual path (show ciphertext for copy) when there's no
        // coordinator at all, or no session yet to encrypt against -
        // coordinator.sendMessage has no way to surface a "no session"
        // status back to this UI, so that case must stay on this path.
        if (coordinator != null && hasSession.value) {
            coordinator.sendMessage(contactRandomId, plaintext)
            return
        }
        viewModelScope.launch {
            val envelope = org.json.JSONObject().put("kind", "text").put("body", plaintext).toString()
            val (result, ciphertext, _) = repository.encryptAndQueue(contactRandomId, envelope)
            when (result) {
                SendResult.Success -> _lastOutgoingCiphertext.value = ciphertext
                SendResult.NoSession -> _statusMessage.value = "No session yet - establish one first."
                is SendResult.Failed -> _statusMessage.value = result.reason
            }
        }
    }

    fun receiveMessage(pastedCiphertext: String, isPreKeyMessage: Boolean) {
        viewModelScope.launch {
            when (val result = repository.receiveAndDecrypt(contactRandomId, pastedCiphertext, isPreKeyMessage)) {
                is ReceiveResult.Success -> _statusMessage.value = null
                is ReceiveResult.SuccessWithIdentityChange ->
                    _statusMessage.value = "Message received, but this contact's identity key changed - re-verify them."
                ReceiveResult.Duplicate -> _statusMessage.value = "Duplicate message ignored (replay protection)."
                is ReceiveResult.Rejected -> _statusMessage.value = "Rejected: ${result.reason}"
                is ReceiveResult.ControlEnvelope ->
                    // An attachment-protocol chunk arrived via manual paste rather
                    // than the automatic transport - not handled here (Phase 7's
                    // attachment flow assumes the coordinator is wiring things up
                    // automatically); tell the user rather than silently dropping it.
                    _statusMessage.value = "That was part of a file transfer, not a text message - use the Attach flow instead."
            }
        }
    }

    val attachmentModeAvailable: Boolean get() = attachmentManager != null

    fun sendAttachment(uri: android.net.Uri) {
        attachmentManager?.sendAttachment(contactRandomId, uri)
    }

    /** Used by the Phase 8 voice-note recorder - already has raw bytes, no Uri/ContentResolver round-trip needed. */
    fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String) {
        attachmentManager?.sendAttachmentBytes(contactRandomId, bytes, fileName, mimeType)
    }

    fun observeAttachment(attachmentId: String): Flow<AttachmentEntity?> =
        attachmentManager?.observeAttachment(attachmentId) ?: kotlinx.coroutines.flow.flowOf(null)

    suspend fun readDecryptedAttachmentFile(fileName: String): ByteArray? =
        attachmentManager?.readDecrypted(fileName)

    val disappearingMessageSeconds: StateFlow<Long?> = contactRepository.observeContacts()
        .map { list -> list.find { it.randomId == contactRandomId }?.disappearingMessageSeconds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Phase 11. Falls back to a local-only update (no peer notification) when there's no live coordinator - still useful, just not synced. */
    fun setDisappearingTimer(seconds: Long?) {
        if (coordinator != null) {
            coordinator.setDisappearingTimer(contactRandomId, seconds)
        } else {
            viewModelScope.launch { repository.setDisappearingMessageSeconds(contactRandomId, seconds) }
        }
    }

    fun destroyConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            destructionManager.destroyConversation(contactRandomId)
            _hasSession.value = false
            onDone()
        }
    }

    fun clearStatus() { _statusMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        coordinator?.endChat(contactRandomId)
    }

    class Factory(
        private val contactRandomId: String,
        private val repository: MessagingRepository,
        private val coordinator: P2PSessionCoordinator?,
        private val attachmentManager: AttachmentTransferManager?,
        private val contactRepository: ContactRepository,
        private val destructionManager: ConversationDestructionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(contactRandomId, repository, coordinator, attachmentManager, contactRepository, destructionManager) as T
    }
}
