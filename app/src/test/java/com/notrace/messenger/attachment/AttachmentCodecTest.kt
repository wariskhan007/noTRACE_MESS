package com.notrace.messenger.attachment

import com.notrace.messenger.attachment.domain.AttachmentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentCodecTest {

    @Test
    fun chunkCount_roundsUp() {
        assertEquals(1, AttachmentCodec.chunkCount(1))
        assertEquals(1, AttachmentCodec.chunkCount(AttachmentCodec.CHUNK_SIZE_BYTES.toLong()))
        assertEquals(2, AttachmentCodec.chunkCount(AttachmentCodec.CHUNK_SIZE_BYTES.toLong() + 1))
    }

    @Test
    fun chunkCount_neverZero() {
        assertEquals(1, AttachmentCodec.chunkCount(0))
    }

    @Test
    fun sha256Hex_isDeterministicAndCorrectLength() {
        val bytes = "hello notrace".toByteArray()
        val hash1 = AttachmentCodec.sha256Hex(bytes)
        val hash2 = AttachmentCodec.sha256Hex(bytes)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // 32 bytes hex-encoded
    }

    @Test
    fun sha256Hex_differsForDifferentInput() {
        val a = AttachmentCodec.sha256Hex("a".toByteArray())
        val b = AttachmentCodec.sha256Hex("b".toByteArray())
        assert(a != b)
    }

    @Test
    fun encodeDecodeChunk_roundTrips() {
        val original = byteArrayOf(1, 2, 3, 4, 5, 0, -1, 127)
        val encoded = AttachmentCodec.encodeChunk(original)
        val decoded = AttachmentCodec.decodeChunk(encoded)
        assertArrayEquals(original, decoded)
    }
}
