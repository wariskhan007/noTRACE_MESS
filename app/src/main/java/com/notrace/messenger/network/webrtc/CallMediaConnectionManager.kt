package com.notrace.messenger.network.webrtc

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

enum class CallMediaConnectionState { NEW, CONNECTING, CONNECTED, RECONNECTING, FAILED, CLOSED }

/**
 * One WebRTC PeerConnection for a call (Phase 8: audio-only; Phase 9:
 * optionally + video). Renamed from Phase 8's CallAudioConnectionManager
 * now that it carries video too - still a SEPARATE class/instance from
 * P2PConnectionManager (Phase 5's messaging data channel), for the same
 * reason as before: a call's lifecycle is independent of chat state.
 *
 * Video capture uses Camera2Enumerator (Camera2 is the modern, actively
 * supported Android camera API - the legacy Camera1 API this could
 * otherwise use is deprecated). Capture resolution is a fixed,
 * conservative default (640x480@24fps) rather than the device's
 * maximum - deliberately: a fixed, modest capture resolution plus
 * WebRTC's own built-in bandwidth estimator (Google Congestion Control,
 * on by default for any video RtpSender) together give real adaptive
 * quality (plan Section 12 "adaptive media quality" / "network
 * adaptation") - WebRTC lowers the actual ENCODED bitrate/framerate to
 * match available bandwidth automatically; this class doesn't need to
 * reimplement that, only avoid capturing at a resolution so high it
 * fights the encoder. A more advanced version could step CAPTURE
 * resolution down too on sustained poor bandwidth (simulcast/SVC) -
 * out of scope here, noted as a real, bounded limitation.
 *
 * Background behavior (plan Section 12): stopVideoCapture()/
 * resumeVideoCapture() let the caller (CallScreen, via a lifecycle
 * observer) release the camera when the app backgrounds and reacquire
 * it on foreground - audio keeps flowing throughout, only video pauses.
 *
 * Privacy indicators: this class does nothing to suppress Android's
 * own camera/microphone-in-use system indicators (the green dot) -
 * they work automatically for any app using Camera2/AudioRecord
 * through normal channels, which is all this does.
 *
 * HIGH RISK-OF-DRIFT NOTE: same caveat as every WebRTC-touching file
 * since Phase 5 - the exact io.github.webrtc-sdk:android Kotlin API
 * for camera capture could not be compiled/verified in this sandbox.
 */
class CallMediaConnectionManager(
    private val context: Context,
    private val withVideo: Boolean,
    stunServerUrl: String = "stun:stun.l.google.com:19302",
    turnServerUrl: String? = null,
    turnUsername: String? = null,
    turnCredential: String? = null
) {
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private val iceServers: List<PeerConnection.IceServer> = buildList {
        add(PeerConnection.IceServer.builder(stunServerUrl).createIceServer())
        if (turnServerUrl != null) {
            add(
                PeerConnection.IceServer.builder(turnServerUrl)
                    .setUsername(turnUsername.orEmpty())
                    .setPassword(turnCredential.orEmpty())
                    .createIceServer()
            )
        }
    }

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var isFrontCamera = true

    private var hasAttemptedIceRestart = false

    private val _connectionState = MutableStateFlow(CallMediaConnectionState.NEW)
    val connectionState: StateFlow<CallMediaConnectionState> = _connectionState.asStateFlow()

    private val _localIceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 16)
    val localIceCandidates: SharedFlow<IceCandidate> = _localIceCandidates.asSharedFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrackFlow: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    fun createOfferAsInitiator(onOfferReady: (SessionDescription) -> Unit) {
        peerConnection = buildPeerConnection()
        addLocalAudioTrack()
        if (withVideo) addLocalVideoTrack()
        peerConnection?.createOffer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver, desc)
                onOfferReady(desc)
            }
        }, MediaConstraints())
    }

    fun acceptOfferAsResponder(offer: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        peerConnection = buildPeerConnection()
        addLocalAudioTrack()
        if (withVideo) addLocalVideoTrack()
        peerConnection?.setRemoteDescription(NoOpSdpObserver, offer)
        peerConnection?.createAnswer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver, desc)
                onAnswerReady(desc)
            }
        }, MediaConstraints())
    }

    fun onAnswerReceived(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(NoOpSdpObserver, answer)
    }

    fun onRemoteIceCandidateReceived(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    /** Enables/disables sending video without tearing down the capturer - the remote side sees a frozen/black frame per WebRTC's normal behavior for a disabled track. */
    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
        isFrontCamera = !isFrontCamera
    }

    /** Releases the camera (background behavior) without ending the call - audio is unaffected. */
    fun stopVideoCapture() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { /* already stopped */ }
    }

    /** Reacquires the camera after stopVideoCapture (app foregrounded again). */
    fun resumeVideoCapture() {
        if (!withVideo) return
        videoCapturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
    }

    fun attemptIceRestart(onOfferReady: (SessionDescription) -> Unit) {
        if (hasAttemptedIceRestart) {
            _connectionState.value = CallMediaConnectionState.FAILED
            return
        }
        hasAttemptedIceRestart = true
        _connectionState.value = CallMediaConnectionState.RECONNECTING
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver, desc)
                onOfferReady(desc)
            }
        }, constraints)
    }

    fun close() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { /* already stopped */ }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        videoSource = null
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _connectionState.value = CallMediaConnectionState.CLOSED
    }

    private fun addLocalAudioTrack() {
        val source = peerConnectionFactory.createAudioSource(MediaConstraints())
        val track = peerConnectionFactory.createAudioTrack("notrace-audio", source)
        peerConnection?.addTrack(track)
        audioSource = source
        localAudioTrack = track
    }

    private fun addLocalVideoTrack() {
        val capturer = createCameraCapturer() ?: return
        val helper = SurfaceTextureHelper.create("notrace-capture-thread", eglBase.eglBaseContext)
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        val track = peerConnectionFactory.createVideoTrack("notrace-video", source)
        peerConnection?.addTrack(track)

        videoCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        localVideoTrack = track
        _localVideoTrack.value = track
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        // Prefer front camera first (typical for a video call self-view).
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = frontCamera ?: deviceNames.firstOrNull() ?: return null
        isFrontCamera = frontCamera != null
        return enumerator.createCapturer(chosen, null)
    }

    private fun buildPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                _localIceCandidates.tryEmit(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                _connectionState.value = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        hasAttemptedIceRestart = false
                        CallMediaConnectionState.CONNECTED
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> CallMediaConnectionState.RECONNECTING
                    PeerConnection.IceConnectionState.FAILED -> CallMediaConnectionState.FAILED
                    PeerConnection.IceConnectionState.CLOSED -> CallMediaConnectionState.CLOSED
                    PeerConnection.IceConnectionState.CHECKING -> CallMediaConnectionState.CONNECTING
                    else -> _connectionState.value
                }
            }

            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }

            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        })
    }

    companion object {
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 24
    }
}

private object NoOpSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
