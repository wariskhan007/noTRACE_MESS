package com.notrace.messenger.network.webrtc

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.charset.StandardCharsets

enum class P2PConnectionState { NEW, CONNECTING, CONNECTED, FAILED, CLOSED }

/**
 * Manages one WebRTC PeerConnection + reliable/ordered DataChannel for
 * one contact. There is no audio/video media here (Phase 8/9) - Phase 5
 * is purely a P2P transport for Phase 4's already-encrypted ciphertext
 * bytes, so it's a data-channel-only connection.
 *
 * ICE server configuration: a public STUN server (for NAT traversal /
 * direct P2P discovery - Phase 0 decision, "direct P2P preference") is
 * included by default. TURN is NOT hardcoded to any real server here -
 * running a TURN relay (e.g. coturn) is infrastructure Hrink would need
 * to stand up and operate himself; TURN_SERVER_URL/credentials are a
 * plain constructor parameter so it's wired in architecturally (ICE
 * will automatically prefer a direct/STUN-derived candidate and only
 * fall back to a TURN relay candidate if direct connectivity fails -
 * that's WebRTC's own standard ICE priority behavior, not something
 * this class has to implement itself) without this project silently
 * depending on a TURN server that doesn't exist yet.
 *
 * HIGH RISK-OF-DRIFT NOTE: same caveat as libsignal in Phase 4 - the
 * exact io.github.webrtc-sdk:android 114.5735.10 Kotlin-facing API
 * could not be compiled/verified in this sandbox (no network access).
 * Written to WebRTC's long-stable, cross-version Java API shape
 * (org.webrtc.* - unchanged across most WebRTC Android forks for years).
 */
class P2PConnectionManager(
    context: Context,
    stunServerUrl: String = "stun:stun.l.google.com:19302",
    turnServerUrl: String? = null,
    turnUsername: String? = null,
    turnCredential: String? = null
) {
    private val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
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
    private var dataChannel: DataChannel? = null

    private val _connectionState = MutableStateFlow(P2PConnectionState.NEW)
    val connectionState: StateFlow<P2PConnectionState> = _connectionState.asStateFlow()

    private val _localIceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 16)
    val localIceCandidates: SharedFlow<IceCandidate> = _localIceCandidates.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    /**
     * Call on the side that initiates the connection (Alice, if she's
     * the one starting the chat). Creates the PeerConnection, a
     * DataChannel, and an SDP offer to send via signaling.
     */
    fun createOfferAsInitiator(onOfferReady: (SessionDescription) -> Unit) {
        peerConnection = buildPeerConnection()
        dataChannel = peerConnection?.createDataChannel(
            "notrace-messages",
            DataChannel.Init().apply { ordered = true }
        )
        dataChannel?.registerObserver(dataChannelObserver())

        peerConnection?.createOffer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(NoOpSdpObserver, desc)
                onOfferReady(desc)
            }
        }, MediaConstraints())
    }

    /** Call on the receiving side once an offer arrives via signaling. */
    fun acceptOfferAsResponder(offer: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        peerConnection = buildPeerConnection()
        // The responder's DataChannel arrives via onDataChannel (see
        // buildPeerConnection's Observer) rather than being created here.

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

    fun sendMessage(text: String): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false
        )
        return channel.send(buffer)
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
        _connectionState.value = P2PConnectionState.CLOSED
    }

    private fun buildPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            // Direct P2P preference (Phase 0 decision): WebRTC's ICE agent
            // tries host/srflx (direct/STUN-derived) candidates before
            // relay (TURN) candidates by priority automatically - no
            // extra logic needed here to "prefer" direct connections.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                _localIceCandidates.tryEmit(candidate)
            }

            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                channel.registerObserver(dataChannelObserver())
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                _connectionState.value = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> P2PConnectionState.CONNECTED
                    PeerConnection.IceConnectionState.FAILED -> P2PConnectionState.FAILED
                    PeerConnection.IceConnectionState.CLOSED -> P2PConnectionState.CLOSED
                    PeerConnection.IceConnectionState.CHECKING -> P2PConnectionState.CONNECTING
                    else -> _connectionState.value
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        })
    }

    private fun dataChannelObserver() = object : DataChannel.Observer {
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            _incomingMessages.tryEmit(String(bytes, StandardCharsets.UTF_8))
        }
        override fun onStateChange() {}
        override fun onBufferedAmountChange(amount: Long) {}
    }
}

/** SdpObserver has 4 no-op-able callbacks; most call sites only care about one. */
private object NoOpSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
