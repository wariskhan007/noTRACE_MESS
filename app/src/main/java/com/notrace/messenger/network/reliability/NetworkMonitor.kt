package com.notrace.messenger.network.reliability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event-driven network-change detection (plan Section 12/13: "Android
 * network callbacks", "event-driven networking over polling"). Used to
 * trigger an IMMEDIATE signaling reconnect attempt the moment
 * connectivity is restored, rather than waiting for
 * SignalingClient's own exponential backoff to happen to land on a
 * retry at the right moment (which could be up to 30 seconds away).
 * The backoff logic itself is untouched and still applies normally if
 * this immediate attempt also fails.
 *
 * Uses ConnectivityManager.NetworkCallback (registerDefaultNetworkCallback)
 * rather than polling ConnectivityManager.activeNetwork on a timer -
 * genuinely event-driven, not a disguised poll loop.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkAvailable: SharedFlow<Unit> = _networkAvailable.asSharedFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkAvailable.tryEmit(Unit)
        }
        // onLost is deliberately not acted on here - SignalingClient's
        // own onFailure/onClosed handling already reacts to the actual
        // socket dying, which is the more reliable signal (a network
        // capability change doesn't always mean the existing socket is
        // actually dead yet, or that a NEW one would fare any better).
    }

    /** Call once (e.g. from AppContainer at first access) - idempotent. */
    fun start() {
        if (registered) return
        registered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }
}
