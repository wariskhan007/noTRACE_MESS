package com.notrace.messenger.attachment.domain

import android.util.Base64
import java.security.MessageDigest

/**
 * Chunk-size math and integrity hashing for the attachment protocol.
 *
 * CHUNK_SIZE_BYTES (24 KB plaintext) is sized so that after Signal
 * Protocol encryption + base64 (the same wire envelope Phase 4/5/6
 * already use for text) + JSON overhead, a single chunk still fits
 * comfortably under the signaling server's MAX_PAYLOAD_BYTES (64 KB,
 * Phase 6) - meaning an attachment chunk can ALSO go through the
 * offline relay fallback exactly like a text message, no separate
 * "large file" path needed. Base64 inflates by ~4/3; JSON/envelope
 * overhead adds a little more; 24 KB * 1.4 ≈ 33.6 KB, safely under 64 KB.
 */
object AttachmentCodec {
    const val CHUNK_SIZE_BYTES = 24 * 1024
    const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024 // 25 MB (Phase 7 "attachment size limits")

    fun chunkCount(totalBytes: Long): Int =
        ((totalBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encodeChunk(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decodeChunk(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
}
