package com.notrace.messenger.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.domain.CryptoIdentityManager
import com.notrace.messenger.crypto.domain.EstablishSessionResult
import com.notrace.messenger.crypto.domain.MessagingRepository
import com.notrace.messenger.crypto.domain.NoTraceProtocolStore
import com.notrace.messenger.crypto.domain.ReceiveResult
import com.notrace.messenger.crypto.domain.SendResult
import com.notrace.messenger.storage.db.NoTraceDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL end-to-end crypto test: two independent local databases/stores
 * ("Alice" and "Bob"), no mocking of libsignal at all.
 *
 * INSTRUMENTED, NOT unit test, on purpose: libsignal-android ships a
 * native (JNI) library built for Android ABIs. That native code cannot
 * load under a host-JVM unit test (Robolectric included) - it needs a
 * real Android runtime (device or emulator), which is what @RunWith
 * (AndroidJUnit4) + this file's location under src/androidTest/ gives
 * it. This is why this test does NOT run as part of CI's
 * `gradle testDebugUnitTest` step, and currently has no automated CI
 * coverage - running it requires `connectedAndroidTest` against a real
 * device/emulator, which this project's GitHub Actions workflow does
 * not set up (no emulator job configured - flagged as a limitation in
 * this phase's report). Until then, this is the executable spec of
 * what Phase 4's crypto guarantees; verify it manually via Android
 * Studio's test runner or a future emulator CI job.
 */
@RunWith(AndroidJUnit4::class)
class MessagingRoundTripTest {

    private fun buildRepo(passphraseSeed: String): MessagingRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = NoTraceDatabase.buildInMemoryForTest(context, passphraseSeed.toCharArray())
        val cryptoIdentityManager = CryptoIdentityManager(
            db.cryptoSelfIdentityDao(), db.oneTimePreKeyDao(), db.signedPreKeyDao()
        )
        val identityKeyPair = runBlocking { cryptoIdentityManager.getOrCreateIdentityKeyPair() }
        val registrationId = runBlocking { cryptoIdentityManager.getLocalRegistrationId() }
        val store = NoTraceProtocolStore(
            identityKeyPair, registrationId,
            db.oneTimePreKeyDao(), db.signedPreKeyDao(), db.remoteIdentityKeyDao(), db.sessionDao()
        )
        return MessagingRepository(cryptoIdentityManager, store, db.sessionDao(), db.messageDao(), db.contactDao())
    }

    @Test
    fun aliceAndBob_canEstablishSessionAndExchangeMessages() = runBlocking {
        val alice = buildRepo("alice-pass")
        val bob = buildRepo("bob-pass")
        val aliceId = "111111111111"
        val bobId = "222222222222"

        // Bob publishes a bundle; Alice consumes it to start a session.
        val bobBundle = bob.exportOwnBundle()
        val establishResult = alice.establishSession(bobId, bobBundle)
        assertEquals(EstablishSessionResult.Success, establishResult)

        // Alice sends the FIRST message (a PreKeySignalMessage under the hood).
        val (sendResult1, ciphertext1) = alice.encryptAndQueue(bobId, "Hello Bob, this is Alice.")
        assertEquals(SendResult.Success, sendResult1)
        assertTrue(ciphertext1 != null)

        val receive1 = bob.receiveAndDecrypt(aliceId, ciphertext1!!, isPreKeyMessage = true)
        assertTrue(receive1 is ReceiveResult.Success)
        assertEquals("Hello Bob, this is Alice.", (receive1 as ReceiveResult.Success).plaintext)

        // Bob replies - now a normal (non-prekey) ratchet message in both directions.
        val (sendResult2, ciphertext2) = bob.encryptAndQueue(aliceId, "Hi Alice, got it.")
        assertEquals(SendResult.Success, sendResult2)
        val receive2 = alice.receiveAndDecrypt(bobId, ciphertext2!!, isPreKeyMessage = false)
        assertTrue(receive2 is ReceiveResult.Success)
        assertEquals("Hi Alice, got it.", (receive2 as ReceiveResult.Success).plaintext)

        // A few more messages each way to exercise ratchet advancement.
        repeat(3) { i ->
            val (r, ct) = alice.encryptAndQueue(bobId, "Alice message $i")
            assertEquals(SendResult.Success, r)
            val recv = bob.receiveAndDecrypt(aliceId, ct!!, isPreKeyMessage = false)
            assertTrue(recv is ReceiveResult.Success)
        }
    }

    @Test
    fun replayedCiphertext_isRejectedAsDuplicate() = runBlocking {
        val alice = buildRepo("alice-pass-2")
        val bob = buildRepo("bob-pass-2")
        val aliceId = "111111111111"
        val bobId = "222222222222"

        val bobBundle = bob.exportOwnBundle()
        alice.establishSession(bobId, bobBundle)

        val (_, ciphertext) = alice.encryptAndQueue(bobId, "Only once, please.")

        val first = bob.receiveAndDecrypt(aliceId, ciphertext!!, isPreKeyMessage = true)
        assertTrue(first is ReceiveResult.Success)

        val replay = bob.receiveAndDecrypt(aliceId, ciphertext, isPreKeyMessage = true)
        assertEquals(ReceiveResult.Duplicate, replay)
    }

    @Test
    fun tamperedCiphertext_isRejected() = runBlocking {
        val alice = buildRepo("alice-pass-3")
        val bob = buildRepo("bob-pass-3")
        val aliceId = "111111111111"
        val bobId = "222222222222"

        val bobBundle = bob.exportOwnBundle()
        alice.establishSession(bobId, bobBundle)

        val (_, ciphertext) = alice.encryptAndQueue(bobId, "Don't tamper with me.")
        val bytes = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte() // flip last byte
        val tampered = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        val result = bob.receiveAndDecrypt(aliceId, tampered, isPreKeyMessage = true)
        assertTrue(result is ReceiveResult.Rejected)
    }

    @Test
    fun outOfOrderMessages_bothStillDecrypt() = runBlocking {
        val alice = buildRepo("alice-pass-4")
        val bob = buildRepo("bob-pass-4")
        val aliceId = "111111111111"
        val bobId = "222222222222"

        val bobBundle = bob.exportOwnBundle()
        alice.establishSession(bobId, bobBundle)

        // First message establishes the session on Bob's side (must be a PreKeySignalMessage).
        val (_, ct1) = alice.encryptAndQueue(bobId, "first")
        bob.receiveAndDecrypt(aliceId, ct1!!, isPreKeyMessage = true)

        // Two more messages sent, delivered out of order.
        val (_, ct2) = alice.encryptAndQueue(bobId, "second")
        val (_, ct3) = alice.encryptAndQueue(bobId, "third")

        val recvThird = bob.receiveAndDecrypt(aliceId, ct3!!, isPreKeyMessage = false)
        val recvSecond = bob.receiveAndDecrypt(aliceId, ct2!!, isPreKeyMessage = false)

        assertTrue(recvThird is ReceiveResult.Success)
        assertTrue(recvSecond is ReceiveResult.Success)
        assertEquals("third", (recvThird as ReceiveResult.Success).plaintext)
        assertEquals("second", (recvSecond as ReceiveResult.Success).plaintext)
    }
}
