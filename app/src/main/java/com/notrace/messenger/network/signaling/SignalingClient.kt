package com.notrace.messenger.network.signaling

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve

enum class SignalingConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, FAILED, RECONNECTING }

/**
 * relayMessageId is non-null when this signal arrived from the Phase 6
 * relay queue (a "queued-item" message) rather than live routing (a
 * "signal" message) - the caller must call SignalingClient.sendAck()
 * with this id once it's done processing, so the server can delete it
 * from the recipient's queue.
 */
data class IncomingSignal(val from: String, val payload: JSONObject, val relayMessageId: String? = null)

private const val RECONNECT_MAX_ATTEMPTS = 8 // bounded - plan Section 13 "no infinite connection-checking loop"

/**
 * Pure exponential-backoff math, pulled out of SignalingClient so it's
 * directly unit-testable without mocking a WebSocket/network at all
 * (see ReconnectBackoffTest) - 2s, 4s, 8s... capped at 30s.
 */
object ReconnectBackoff {
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    fun delayForAttempt(attempt: Int): Long =
        (BASE_DELAY_MS * (1L shl attempt)).coerceAtMost(MAX_DELAY_MS)
}

/**
 * WebSocket client implementing the handshake protocol described in
 * signaling-server/index.js: hello -> challenge -> auth -> ready, then
 * free routing of opaque `signal` payloads.
 *
 * One instance per app process (held in AppContainer), connects lazily
 * when first needed (opening a chat) rather than eagerly at launch, to
 * avoid holding a socket open for an app that's just sitting on the
 * contacts list (battery/plan Section 12 "performance and reliability"
 * consideration, addressed properly here rather than deferred).
 *
 * Reconnection (Phase 12, plan Section 13's own example: "retry after
 * short delay -> increasing retry delay -> maximum retry limit -> wait
 * for network/app event"): an UNEXPECTED disconnect (not a deliberate
 * disconnect() call) triggers automatic reconnection with exponential
 * backoff (2s, 4s, 8s... capped at 30s), up to RECONNECT_MAX_ATTEMPTS.
 * After that, it stops retrying on its own and waits for an explicit
 * connect() call (e.g. the user reopening a chat, which
 * P2PSessionCoordinator.startChat already does) - never an infinite
 * loop. A successful connection resets the attempt counter.
 *
 * HIGH RISK-OF-DRIFT NOTE (same caveat as Phase 4's protocol store):
 * OkHttp's WebSocket API is long-stable and low-risk; the actual
 * uncertainty here is entirely on the server side's
 * @signalapp/libsignal-client Node API, not this file.
 */
class SignalingClient(
    private var serverUrl: String,
    private val myRandomId: String,
    private val identityKeyPair: IdentityKeyPair,
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var deliberateDisconnect = false

    /**
     * SignalingClient is built lazily (AppContainer) the first time a
     * chat is opened, capturing whatever URL was in Settings at that
     * moment. If the user configures/changes the URL afterward, call
     * this before connect() so a stale blank/old URL isn't reused.
     */
    fun updateServerUrl(url: String) {
        serverUrl = url
    }

    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _incomingSignals = MutableSharedFlow<IncomingSignal>(extraBufferCapacity = 32)
    val incomingSignals: SharedFlow<IncomingSignal> = _incomingSignals.asSharedFlow()

    private val _peerOfflineEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val peerOfflineEvents: SharedFlow<String> = _peerOfflineEvents.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect() {
        if (serverUrl.isBlank()) {
            _connectionState.value = SignalingConnectionState.FAILED
            _lastError.value = "No signaling server configured."
            return
        }
        if (_connectionState.value == SignalingConnectionState.READY ||
            _connectionState.value == SignalingConnectionState.CONNECTING
        ) return

        deliberateDisconnect = false
        reconnectJob?.cancel()
        _connectionState.value = SignalingConnectionState.CONNECTING
        val request = Request.Builder().url(serverUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun disconnect() {
        deliberateDisconnect = true
        reconnectJob?.cancel()
        reconnectAttempt = 0
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }

    /**
     * Schedules one reconnect attempt after an exponential backoff
     * delay, unless the disconnect was deliberate (disconnect() was
     * called) or the attempt budget is exhausted - see class doc.
     */
    private fun scheduleReconnect() {
        if (deliberateDisconnect || serverUrl.isBlank()) return
        if (reconnectAttempt >= RECONNECT_MAX_ATTEMPTS) {
            _connectionState.value = SignalingConnectionState.FAILED
            return
        }
        val delayMs = ReconnectBackoff.delayForAttempt(reconnectAttempt)
        reconnectAttempt++
        _connectionState.value = SignalingConnectionState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!deliberateDisconnect) {
                _connectionState.value = SignalingConnectionState.CONNECTING
                val request = Request.Builder().url(serverUrl).build()
                webSocket = okHttpClient.newWebSocket(request, listener)
            }
        }
    }

    fun sendSignal(to: String, payload: JSONObject) {
        val message = JSONObject().apply {
            put("type", "signal")
            put("to", to)
            put("payload", payload)
        }
        webSocket?.send(message.toString())
    }

    /** Acknowledges a Phase 6 relay-queue item so the server deletes it. Call after processing, success or not. */
    fun sendAck(relayMessageId: String) {
        val message = JSONObject().apply {
            put("type", "ack")
            put("id", relayMessageId)
        }
        webSocket?.send(message.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionState.value = SignalingConnectionState.AUTHENTICATING
            val hello = JSONObject().apply {
                put("type", "hello")
                put("randomId", myRandomId)
                put("identityPublicKey", b64(identityKeyPair.publicKey.serialize()))
            }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try { JSONObject(text) } catch (e: Exception) { return }
            when (json.optString("type")) {
                "challenge" -> handleChallenge(webSocket, json)
                "ready" -> {
                    _connectionState.value = SignalingConnectionState.READY
                    reconnectAttempt = 0 // fresh success - full retry budget again if it drops later
                }
                "signal" -> {
                    val from = json.optString("from")
                    val payload = json.optJSONObject("payload") ?: return
                    _incomingSignals.tryEmit(IncomingSignal(from, payload))
                }
                "queued-item" -> {
                    val from = json.optString("from")
                    val payload = json.optJSONObject("payload") ?: return
                    val id = json.optString("id")
                    _incomingSignals.tryEmit(IncomingSignal(from, payload, relayMessageId = id))
                }
                "queued" -> {
                    // Confirms a "relayed-message" send was HELD server-side
                    // (Phase 6), not dropped - informational only; the
                    // message is already marked SENT locally regardless.
                }
                "peer-offline" -> {
                    val to = json.optString("to")
                    _peerOfflineEvents.tryEmit(to)
                }
                "error" -> {
                    _lastError.value = json.optString("reason", "unknown-error")
                    if (json.optString("reason") == "identity-mismatch" || json.optString("reason") == "signature-invalid") {
                        _connectionState.value = SignalingConnectionState.FAILED
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _lastError.value = t.message ?: "Connection failed"
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (deliberateDisconnect) {
                _connectionState.value = SignalingConnectionState.DISCONNECTED
            } else {
                scheduleReconnect()
            }
        }
    }

    private fun handleChallenge(webSocket: WebSocket, json: JSONObject) {
        val nonce = unb64(json.getString("nonce"))
        // Same primitive Phase 4 already uses to sign a signed prekey -
        // no new cryptography introduced (plan rule #4).
        val signature = Curve.calculateSignature(identityKeyPair.privateKey, nonce)
        val authMessage = JSONObject().apply {
            put("type", "auth")
            put("signature", b64(signature))
        }
        webSocket.send(authMessage.toString())
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
