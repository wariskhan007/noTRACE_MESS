package com.notrace.messenger.crypto.domain

import android.util.Base64
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Serializes/parses a PreKeyBundle to/from a compact Base64 blob so it
 * can be shared out-of-band (copy/paste, QR code) - standing in for the
 * real distribution transport that lands in Phase 5. This is the same
 * *content* a signaling server would hand over automatically; only the
 * *delivery mechanism* is manual for now.
 *
 * Format is a flat JSON object, base64'd as a whole for easy copy-paste:
 * {"rid": registrationId, "dev": deviceId, "pkid": preKeyId,
 *  "pk": base64(preKeyPublic), "spkid": signedPreKeyId,
 *  "spk": base64(signedPreKeyPublic), "spksig": base64(signature),
 *  "idk": base64(identityKey)}
 *
 * pkid/pk are omitted (null) if the exporter's one-time prekey pool was
 * exhausted - libsignal's PreKeyBundle and SessionBuilder both support
 * a bundle with no one-time prekey (X3DH degrades gracefully to using
 * only the signed prekey, at a small reduction in forward secrecy for
 * that initial handshake only, per the Signal Protocol spec).
 */
object BundleCodec {

    fun encode(
        registrationId: Int,
        oneTimePreKey: PreKeyRecord?,
        signedPreKey: SignedPreKeyRecord,
        identityKey: IdentityKey
    ): String {
        val json = JSONObject().apply {
            put("rid", registrationId)
            put("dev", 1) // single-device V1 (Phase 0 decision #8)
            if (oneTimePreKey != null) {
                put("pkid", oneTimePreKey.id)
                put("pk", b64(oneTimePreKey.keyPair.publicKey.serialize()))
            }
            put("spkid", signedPreKey.id)
            put("spk", b64(signedPreKey.keyPair.publicKey.serialize()))
            put("spksig", b64(signedPreKey.signature))
            put("idk", b64(identityKey.serialize()))
        }
        return b64(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun decode(encoded: String): PreKeyBundle {
        val json = JSONObject(String(unb64(encoded), Charsets.UTF_8))
        val identityKey = IdentityKey(unb64(json.getString("idk")), 0)
        val signedPreKeyPublic: ECPublicKey = Curve.decodePoint(unb64(json.getString("spk")), 0)

        val hasOneTimePreKey = json.has("pkid")
        val preKeyId = if (hasOneTimePreKey) json.getInt("pkid") else -1
        val preKeyPublic: ECPublicKey? = if (hasOneTimePreKey) Curve.decodePoint(unb64(json.getString("pk")), 0) else null

        return PreKeyBundle(
            json.getInt("rid"),
            json.getInt("dev"),
            preKeyId,
            preKeyPublic,
            json.getInt("spkid"),
            signedPreKeyPublic,
            unb64(json.getString("spksig")),
            identityKey
        )
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
