package com.notrace.messenger.call.domain

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import com.notrace.messenger.call.service.CallForegroundService
import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.network.signaling.IncomingSignal
import com.notrace.messenger.network.signaling.SignalingClient
import com.notrace.messenger.network.webrtc.CallMediaConnectionManager
import com.notrace.messenger.network.webrtc.CallMediaConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

private const val CALL_INVITE_TIMEOUT_MS = 45_000L
private const val ENDED_STATE_DISPLAY_MS = 3_000L

/**
 * App-wide call state machine and orchestration (plan Section 12).
 * There is exactly ONE call at a time (single-device V1, Phase 0
 * decision #8) - a second incoming invite while already on/starting a
 * call is answered with call-reject/reason=busy automatically, never
 * silently dropped and never interrupts the current call.
 *
 * Wire protocol (signaling payload kinds, distinct from Phase 5's
 * messaging "sdp-offer"/"sdp-answer"/"ice-candidate" so the two
 * PeerConnections - messaging data channel vs. call media - never get
 * confused with each other):
 *   {kind:"call-invite", callId, video: true|false}
 *   {kind:"call-accept", callId}
 *   {kind:"call-reject", callId, reason:"declined"|"busy"}
 *   {kind:"call-end", callId, reason:"hangup"|"timeout"|"failed"}
 *   {kind:"call-sdp-offer", callId, sdp, type}
 *   {kind:"call-sdp-answer", callId, sdp, type}
 *   {kind:"call-ice-candidate", callId, sdpMid, sdpMLineIndex, candidate}
 * None of these are ever queued in Phase 6's offline relay (a call
 * invite to someone offline is meaningless) - they only ever reach an
 * online peer, exactly like Phase 5's other handshake-only kinds.
 *
 * Video (Phase 9): the "video" flag on call-invite is the ONLY new
 * wire field - it's set by whoever calls startCall(isVideo=true), and
 * the callee's acceptCall() reads it back off the current CallUiState
 * to build its own CallMediaConnectionManager with matching
 * withVideo. A call's video-ness is fixed for its whole lifetime (no
 * mid-call upgrade from audio to video in V1 - a reasonable, disclosed
 * scope cut; the callee could of course simply hang up and re-call
 * with video instead).
 *
 * Convention for who creates the SDP offer: whoever RECEIVES
 * call-accept creates it (i.e. the original caller) - not before,
 * so no WebRTC negotiation happens for a call that gets rejected or
 * times out unanswered.
 */
class CallManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val callConnectionFactory: (withVideo: Boolean) -> CallMediaConnectionManager,
    private val messageDao: MessageDao,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var connection: CallMediaConnectionManager? = null
    private var timeoutJob: Job? = null
    private var audioManager: AudioManager? = null

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrackFlow: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    val eglBaseContext get() = connection?.eglBaseContext

    init {
        signalingClient.incomingSignals.onEach { handleSignal(it) }.launchIn(scope)
    }

    fun startCall(contactRandomId: String, isVideo: Boolean) {
        if (_state.value.phase != CallPhase.IDLE) return // already on/starting a call

        val callId = UUID.randomUUID().toString()
        _state.value = CallUiState(phase = CallPhase.DIALING, contactRandomId = contactRandomId, callId = callId, isVideo = isVideo)
        signalingClient.sendSignal(contactRandomId, JSONObject().put("kind", "call-invite").put("callId", callId).put("video", isVideo))

        timeoutJob = scope.launch {
            kotlinx.coroutines.delay(CALL_INVITE_TIMEOUT_MS)
            if (_state.value.phase == CallPhase.DIALING && _state.value.callId == callId) {
                signalingClient.sendSignal(contactRandomId, JSONObject().put("kind", "call-end").put("callId", callId).put("reason", "timeout"))
                endLocally(CallEndReason.TIMEOUT)
            }
        }
    }

    fun acceptCall() {
        val current = _state.value
        if (current.phase != CallPhase.RINGING_INCOMING) return
        val contactId = current.contactRandomId ?: return
        val callId = current.callId ?: return

        val newConnection = callConnectionFactory(current.isVideo)
        connection = newConnection
        observeConnection(newConnection)
        _state.value = current.copy(phase = CallPhase.CONNECTING)
        startForegroundNotification(contactId, current.isVideo)
        signalingClient.sendSignal(contactId, JSONObject().put("kind", "call-accept").put("callId", callId))
        // Offer arrives from the caller next (see class doc convention).
    }

    fun rejectCall() {
        val current = _state.value
        if (current.phase != CallPhase.RINGING_INCOMING) return
        val contactId = current.contactRandomId ?: return
        val callId = current.callId ?: return
        signalingClient.sendSignal(contactId, JSONObject().put("kind", "call-reject").put("callId", callId).put("reason", "declined"))
        logCall(contactId, MessageDirection.INCOMING, "Missed call")
        endLocally(CallEndReason.DECLINED)
    }

    fun endCall() {
        val current = _state.value
        if (current.phase == CallPhase.IDLE || current.phase == CallPhase.ENDED) return
        current.contactRandomId?.let { contactId ->
            current.callId?.let { callId ->
                signalingClient.sendSignal(contactId, JSONObject().put("kind", "call-end").put("callId", callId).put("reason", "hangup"))
            }
        }
        endLocally(CallEndReason.HANGUP)
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        connection?.setMuted(newMuted)
        _state.value = _state.value.copy(isMuted = newMuted)
    }

    fun toggleSpeaker() {
        val newSpeakerOn = !_state.value.isSpeakerOn
        (audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .also { audioManager = it }
            .isSpeakerphoneOn = newSpeakerOn
        _state.value = _state.value.copy(isSpeakerOn = newSpeakerOn)
    }

    fun toggleCamera() {
        if (!_state.value.isVideo) return
        val newEnabled = !_state.value.isCameraEnabled
        connection?.setCameraEnabled(newEnabled)
        _state.value = _state.value.copy(isCameraEnabled = newEnabled)
    }

    fun switchCamera() {
        connection?.switchCamera()
    }

    /** Called by CallScreen's lifecycle observer when the app backgrounds - releases the camera, keeps audio flowing. */
    fun onAppBackgrounded() {
        connection?.stopVideoCapture()
    }

    /** Called when the app foregrounds again mid-call. */
    fun onAppForegrounded() {
        if (_state.value.isVideo && _state.value.isCameraEnabled) connection?.resumeVideoCapture()
    }

    private fun handleSignal(signal: IncomingSignal) {
        val payload = signal.payload
        val kind = payload.optString("kind")
        if (!kind.startsWith("call-")) return // not a call-related payload; P2PSessionCoordinator handles the rest

        val contactId = signal.from
        val callId = payload.optString("callId")
        val current = _state.value

        when (kind) {
            "call-invite" -> {
                if (current.phase != CallPhase.IDLE) {
                    signalingClient.sendSignal(contactId, JSONObject().put("kind", "call-reject").put("callId", callId).put("reason", "busy"))
                    return
                }
                _state.value = CallUiState(
                    phase = CallPhase.RINGING_INCOMING, contactRandomId = contactId, callId = callId,
                    isVideo = payload.optBoolean("video", false)
                )
            }
            "call-accept" -> {
                if (current.phase != CallPhase.DIALING || current.callId != callId) return
                timeoutJob?.cancel()
                val newConnection = callConnectionFactory(current.isVideo)
                connection = newConnection
                observeConnection(newConnection)
                _state.value = current.copy(phase = CallPhase.CONNECTING)
                startForegroundNotification(contactId, current.isVideo)
                newConnection.createOfferAsInitiator { offer ->
                    signalingClient.sendSignal(
                        contactId,
                        JSONObject().put("kind", "call-sdp-offer").put("callId", callId).put("sdp", offer.description).put("type", offer.type.canonicalForm())
                    )
                }
            }
            "call-reject" -> {
                if (current.callId != callId) return
                timeoutJob?.cancel()
                logCall(contactId, MessageDirection.OUTGOING, if (payload.optString("reason") == "busy") "Call - contact busy" else "Call declined")
                endLocally(if (payload.optString("reason") == "busy") CallEndReason.BUSY else CallEndReason.DECLINED)
            }
            "call-sdp-offer" -> {
                if (current.callId != callId) return
                val offer = SessionDescription(SessionDescription.Type.OFFER, payload.getString("sdp"))
                connection?.acceptOfferAsResponder(offer) { answer ->
                    signalingClient.sendSignal(
                        contactId,
                        JSONObject().put("kind", "call-sdp-answer").put("callId", callId).put("sdp", answer.description).put("type", answer.type.canonicalForm())
                    )
                }
            }
            "call-sdp-answer" -> {
                if (current.callId != callId) return
                connection?.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp")))
            }
            "call-ice-candidate" -> {
                if (current.callId != callId) return
                connection?.onRemoteIceCandidateReceived(
                    IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate"))
                )
            }
            "call-end" -> {
                if (current.callId != callId) return
                val durationSeconds = current.connectedAtEpochMillis?.let { (System.currentTimeMillis() - it) / 1000 }
                logCall(
                    contactId, MessageDirection.INCOMING,
                    if (durationSeconds != null) "Call ended (${durationSeconds}s)" else "Missed call"
                )
                endLocally(CallEndReason.REMOTE_ENDED)
            }
        }
    }

    private fun observeConnection(mgr: CallMediaConnectionManager) {
        mgr.localVideoTrackFlow.onEach { _localVideoTrack.value = it }.launchIn(scope)
        mgr.remoteVideoTrackFlow.onEach { _remoteVideoTrack.value = it }.launchIn(scope)

        mgr.localIceCandidates.onEach { candidate ->
            val contactId = _state.value.contactRandomId ?: return@onEach
            val callId = _state.value.callId ?: return@onEach
            signalingClient.sendSignal(
                contactId,
                JSONObject().put("kind", "call-ice-candidate").put("callId", callId)
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp)
            )
        }.launchIn(scope)

        mgr.connectionState.onEach { connState ->
            val current = _state.value
            when (connState) {
                CallMediaConnectionState.CONNECTED -> {
                    _state.value = current.copy(
                        phase = CallPhase.CONNECTED,
                        connectedAtEpochMillis = current.connectedAtEpochMillis ?: System.currentTimeMillis()
                    )
                }
                CallMediaConnectionState.RECONNECTING -> {
                    if (current.phase == CallPhase.CONNECTED) _state.value = current.copy(phase = CallPhase.RECONNECTING)
                }
                CallMediaConnectionState.FAILED -> {
                    if (current.phase == CallPhase.RECONNECTING) {
                        // Already tried once (CallMediaConnectionManager's own
                        // one-shot ICE-restart budget) - give up.
                        val durationSeconds = current.connectedAtEpochMillis?.let { (System.currentTimeMillis() - it) / 1000 }
                        current.contactRandomId?.let { logCall(it, MessageDirection.OUTGOING, "Call ended (${durationSeconds ?: 0}s) - connection lost") }
                        endLocally(CallEndReason.FAILED)
                    } else if (current.phase == CallPhase.CONNECTING) {
                        endLocally(CallEndReason.FAILED)
                    }
                }
                else -> Unit
            }
        }.launchIn(scope)
    }

    private fun endLocally(reason: CallEndReason) {
        timeoutJob?.cancel()
        connection?.close()
        connection = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        stopForegroundNotification()
        _state.value = _state.value.copy(phase = CallPhase.ENDED, endReason = reason)
        scope.launch {
            kotlinx.coroutines.delay(ENDED_STATE_DISPLAY_MS)
            if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        }
    }

    private fun logCall(contactRandomId: String, direction: MessageDirection, body: String) {
        scope.launch {
            messageDao.insert(
                MessageEntity(
                    contactRandomId = contactRandomId, direction = direction, body = body,
                    sentOrReceivedAtEpochMillis = System.currentTimeMillis(), deliveryState = DeliveryState.DELIVERED
                )
            )
        }
    }

    private fun startForegroundNotification(contactLabel: String, isVideo: Boolean) {
        val intent = Intent(context, CallForegroundService::class.java)
            .putExtra(CallForegroundService.EXTRA_CONTACT_LABEL, contactLabel)
            .putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun stopForegroundNotification() {
        context.stopService(Intent(context, CallForegroundService::class.java))
    }
}
