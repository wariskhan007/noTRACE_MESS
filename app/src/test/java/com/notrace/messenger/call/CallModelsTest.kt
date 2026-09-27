package com.notrace.messenger.call

import com.notrace.messenger.call.domain.CallEndReason
import com.notrace.messenger.call.domain.CallPhase
import com.notrace.messenger.call.domain.CallUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure data-class/enum tests for the call state model - no WebRTC or
 * libsignal involved, so these run as normal host-JVM unit tests
 * (unlike the actual call connection logic, which needs a real device;
 * same constraint as Phase 4/5's crypto/WebRTC round-trip tests).
 */
class CallModelsTest {

    @Test
    fun defaultState_isIdleWithNoContact() {
        val state = CallUiState()
        assertEquals(CallPhase.IDLE, state.phase)
        assertNull(state.contactRandomId)
        assertNull(state.callId)
    }

    @Test
    fun copy_preservesUnrelatedFields() {
        val state = CallUiState(phase = CallPhase.CONNECTED, contactRandomId = "111122223333", callId = "abc", isMuted = true)
        val updated = state.copy(isSpeakerOn = true)

        assertEquals(CallPhase.CONNECTED, updated.phase)
        assertEquals("111122223333", updated.contactRandomId)
        assertEquals(true, updated.isMuted)
        assertEquals(true, updated.isSpeakerOn)
    }

    @Test
    fun endReason_survivesInEndedState() {
        val state = CallUiState(phase = CallPhase.ENDED, endReason = CallEndReason.TIMEOUT)
        assertEquals(CallEndReason.TIMEOUT, state.endReason)
    }

    @Test
    fun isVideo_defaultsFalseAndIsIndependentOfOtherFields() {
        val audioCall = CallUiState(phase = CallPhase.CONNECTED)
        val videoCall = CallUiState(phase = CallPhase.CONNECTED, isVideo = true)

        assertEquals(false, audioCall.isVideo)
        assertEquals(true, videoCall.isVideo)
        assertEquals(true, videoCall.isCameraEnabled) // camera defaults on for a video call
    }
}
