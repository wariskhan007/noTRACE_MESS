package com.notrace.messenger.storage

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.storage.keys.DatabaseKeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric so EncryptedSharedPreferences/Keystore-backed
 * code can execute on the JVM without a real device/emulator.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseKeyManagerTest {

    @Test
    fun getOrCreatePassphrase_isStableAcrossCalls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = DatabaseKeyManager(context)

        val first = manager.getOrCreatePassphrase()
        val second = manager.getOrCreatePassphrase()

        assertArrayEquals(first, second)
    }

    @Test
    fun getOrCreatePassphrase_is256BitsHexEncoded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = DatabaseKeyManager(context)

        val passphrase = manager.getOrCreatePassphrase()

        // 32 random bytes hex-encoded = 64 hex characters.
        assertEquals(64, passphrase.size)
    }

    @Test
    fun deletePassphrase_causesNewOneToBeGeneratedNext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = DatabaseKeyManager(context)

        val original = manager.getOrCreatePassphrase()
        manager.deletePassphrase()
        val regenerated = manager.getOrCreatePassphrase()

        // Extremely unlikely to collide if truly re-generated (256-bit random).
        assert(!original.contentEquals(regenerated))
    }
}
