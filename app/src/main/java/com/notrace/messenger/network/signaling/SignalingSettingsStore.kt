package com.notrace.messenger.network.signaling

import android.content.Context

/**
 * Where the signaling server URL is persisted. Plain (not encrypted)
 * SharedPreferences deliberately - this is a server address the user
 * typed in, not a secret, so it doesn't need the EncryptedSharedPreferences
 * machinery Phase 2's DatabaseKeyManager uses for the actual DB passphrase.
 */
class SignalingSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("notrace_settings", Context.MODE_PRIVATE)

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER_URL, "wss://notrace-mess.onrender.com")
            ?: "wss://notrace-mess.onrender.com"

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "signaling_server_url"
    }
}
