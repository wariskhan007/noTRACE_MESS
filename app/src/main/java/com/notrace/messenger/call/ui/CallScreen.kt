package com.notrace.messenger.call.ui

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.notrace.messenger.call.domain.CallManager
import com.notrace.messenger.call.domain.CallPhase
import com.notrace.messenger.identity.domain.RandomIdGenerator
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen call UI, shown as an overlay above whatever else is on
 * screen whenever CallManager's phase isn't IDLE (wired at the
 * top-level NavHost, not as a normal nav destination - a call can
 * start while the user is anywhere in the app, e.g. on the contacts
 * list, not just inside that contact's chat).
 *
 * Phase 9: renders remote video full-screen and local video as a small
 * picture-in-picture box when the call is a video call. A
 * lifecycle observer calls CallManager.onAppBackgrounded()/
 * onAppForegrounded() so the camera releases when the app is backgrounded
 * (plan Section 12 "background behavior") - audio keeps flowing either way.
 */
@Composable
fun CallScreen(callManager: CallManager) {
    val state by callManager.state.collectAsState()
    val contactLabel = state.contactRandomId?.let { RandomIdGenerator.format(it) } ?: ""
    val localVideoTrack by callManager.localVideoTrackFlow.collectAsState()
    val remoteVideoTrack by callManager.remoteVideoTrackFlow.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> callManager.onAppBackgrounded()
                Lifecycle.Event.ON_START -> callManager.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isVideo && (state.phase == CallPhase.CONNECTED || state.phase == CallPhase.RECONNECTING)) {
                remoteVideoTrack?.let { track ->
                    VideoRendererView(track = track, eglBaseContext = callManager.eglBaseContext, modifier = Modifier.fillMaxSize())
                }
                localVideoTrack?.let { track ->
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(120.dp, 160.dp)) {
                        VideoRendererView(track = track, eglBaseContext = callManager.eglBaseContext, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(contactLabel)
                Text(phaseLabel(state.phase, state.endReason?.name))

                when (state.phase) {
                    CallPhase.RINGING_INCOMING -> {
                        Row(modifier = Modifier.padding(top = 24.dp)) {
                            Button(onClick = callManager::acceptCall, modifier = Modifier.padding(end = 16.dp)) { Text("Accept") }
                            Button(onClick = callManager::rejectCall) { Text("Decline") }
                        }
                    }
                    CallPhase.DIALING, CallPhase.CONNECTING, CallPhase.CONNECTED, CallPhase.RECONNECTING -> {
                        Row(modifier = Modifier.padding(top = 24.dp)) {
                            Button(onClick = callManager::toggleMute, modifier = Modifier.padding(end = 8.dp)) {
                                Text(if (state.isMuted) "Unmute" else "Mute")
                            }
                            Button(onClick = callManager::toggleSpeaker, modifier = Modifier.padding(end = 8.dp)) {
                                Text(if (state.isSpeakerOn) "Speaker off" else "Speaker on")
                            }
                            if (state.isVideo) {
                                Button(onClick = callManager::toggleCamera, modifier = Modifier.padding(end = 8.dp)) {
                                    Text(if (state.isCameraEnabled) "Camera off" else "Camera on")
                                }
                                Button(onClick = callManager::switchCamera, modifier = Modifier.padding(end = 8.dp)) {
                                    Text("Flip")
                                }
                            }
                            Button(onClick = callManager::endCall) { Text("Hang up") }
                        }
                    }
                    CallPhase.ENDED, CallPhase.FAILED, CallPhase.BUSY, CallPhase.IDLE -> Unit
                }
            }
        }
    }
}

@Composable
private fun VideoRendererView(track: VideoTrack, eglBaseContext: org.webrtc.EglBase.Context?, modifier: Modifier = Modifier) {
    if (eglBaseContext == null) return
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                track.addSink(this)
            }
        },
        onRelease = { renderer ->
            track.removeSink(renderer)
            renderer.release()
        }
    )
}

private fun phaseLabel(phase: CallPhase, endReason: String?): String = when (phase) {
    CallPhase.DIALING -> "Calling…"
    CallPhase.RINGING_INCOMING -> "Incoming call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.CONNECTED -> "Connected"
    CallPhase.RECONNECTING -> "Reconnecting…"
    CallPhase.ENDED -> "Call ended" + (endReason?.let { " ($it)" } ?: "")
    CallPhase.FAILED -> "Call failed"
    CallPhase.BUSY -> "Busy"
    CallPhase.IDLE -> ""
}
