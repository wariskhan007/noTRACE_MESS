package com.notrace.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.notrace.messenger.call.domain.CallPhase
import com.notrace.messenger.call.ui.CallScreen
import com.notrace.messenger.network.signaling.SignalingConnectionState
import com.notrace.messenger.ui.nav.NoTraceNavHost
import com.notrace.messenger.ui.theme.NoTraceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NoTraceApplication).container

        // Phase 8 note: an incoming call (or message) can only ever be
        // RECEIVED while this device has a live, authenticated
        // connection to the signaling server - Phase 5-7 only opened
        // that connection when a specific chat was opened, which meant
        // a call could never reach the user while sitting on, say, the
        // contacts list. Connecting once here (if a server is
        // configured at all) means calls/messages can arrive anywhere
        // in the app while it's foregrounded.
        //
        // Known, disclosed limitation: this is still foreground-only.
        // Receiving anything while the app is backgrounded or killed
        // needs push-notification wake-up (FCM or similar) to open a
        // connection on demand - a substantially bigger undertaking
        // (a push relay is itself new infrastructure, with its own
        // privacy/metadata review) that's out of scope here. Flagging
        // this plainly rather than quietly pretending it's solved.
        if (container.signalingSettingsStore.getServerUrl().isNotBlank() &&
            container.signalingClient.connectionState.value == SignalingConnectionState.DISCONNECTED
        ) {
            container.signalingClient.connect()
        }

        setContent {
            NoTraceTheme {
                val callState by container.callManager.state.collectAsState()
                if (callState.phase == CallPhase.IDLE) {
                    NoTraceNavHost(container = container)
                } else {
                    // Full-screen overlay, replacing normal navigation for
                    // the duration of the call - matches how a phone's own
                    // dialer takes over the screen for an incoming call.
                    CallScreen(callManager = container.callManager)
                }
            }
        }
    }
}
