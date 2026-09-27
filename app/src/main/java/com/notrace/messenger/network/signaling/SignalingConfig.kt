package com.notrace.messenger.network.signaling

/**
 * Where to find the signaling server. This is intentionally NOT
 * hardcoded to any real deployed server - there isn't one until Hrink
 * deploys signaling-server/ himself (see its README.md). Empty by
 * default; the app treats an empty URL as "signaling not configured"
 * and falls back to Phase 4's manual bundle/ciphertext copy-paste
 * (still present in ChatScreen) rather than crashing or silently
 * failing.
 *
 * Stored in Settings (Phase 3's SettingsScreen gains a field for this),
 * persisted via the same EncryptedSharedPreferences mechanism as the
 * DB passphrase (Phase 2's DatabaseKeyManager pattern) - it's not
 * secret, but keeping all small persisted settings in one well-audited
 * place beats inventing a second plain SharedPreferences file.
 */
object SignalingDefaults {
    const val PRODUCTION_URL = "wss://notrace-mess.onrender.com"
    const val LOCAL_EMULATOR_URL = "ws://10.0.2.2:8080"
}
