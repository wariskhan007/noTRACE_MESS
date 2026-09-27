package com.notrace.messenger.call.domain

/**
 * Call lifecycle state (plan Section 12 "call state machine").
 *
 * Transitions:
 *  IDLE -> DIALING (startCall)
 *  IDLE -> RINGING_INCOMING (call-invite received)
 *  DIALING -> CONNECTING (call-accept received) -> CONNECTED (ICE connects)
 *  DIALING -> ENDED (call-reject received, or local timeout)
 *  RINGING_INCOMING -> CONNECTING (acceptCall) -> CONNECTED
 *  RINGING_INCOMING -> ENDED (rejectCall, or call-end received)
 *  CONNECTED -> RECONNECTING (ICE disconnected) -> CONNECTED (self-healed)
 *                                                -> FAILED (ICE restart also failed)
 *  any non-IDLE/ENDED state -> ENDED (endCall / call-end received / error)
 */
enum class CallPhase { IDLE, DIALING, RINGING_INCOMING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED, BUSY }

enum class CallEndReason { HANGUP, DECLINED, BUSY, TIMEOUT, FAILED, REMOTE_ENDED }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val contactRandomId: String? = null,
    val callId: String? = null,
    val isVideo: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val connectedAtEpochMillis: Long? = null,
    val endReason: CallEndReason? = null
)
