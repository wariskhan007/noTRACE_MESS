package com.notrace.messenger.storage

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.identity.domain.UsernameChangeResult
import com.notrace.messenger.storage.db.NoTraceDatabase
import com.notrace.messenger.storage.wipe.StorageWipeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun getOrCreateSelfIdentity_isStableAcrossCalls() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val repo = IdentityRepository(db.selfIdentityDao(), StorageWipeManager(context))

        val first = repo.getOrCreateSelfIdentity()
        val second = repo.getOrCreateSelfIdentity()

        assertEquals(first.randomId, second.randomId)
        assertEquals(0, first.identityEpoch)
        db.close()
    }

    @Test
    fun setUsername_rejectsTooLong() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val repo = IdentityRepository(db.selfIdentityDao(), StorageWipeManager(context))
        repo.getOrCreateSelfIdentity()

        val result = repo.setUsername("a".repeat(33))

        assertEquals(UsernameChangeResult.TooLong, result)
        db.close()
    }

    @Test
    fun setUsername_rejectsInvalidCharacters() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val repo = IdentityRepository(db.selfIdentityDao(), StorageWipeManager(context))
        repo.getOrCreateSelfIdentity()

        val result = repo.setUsername("bad name!")

        assertEquals(UsernameChangeResult.InvalidCharacters, result)
        db.close()
    }

    @Test
    fun setUsername_doesNotChangeRandomId() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val repo = IdentityRepository(db.selfIdentityDao(), StorageWipeManager(context))
        val before = repo.getOrCreateSelfIdentity()

        repo.setUsername("hrink_test")
        val after = repo.getOrCreateSelfIdentity()

        assertEquals(before.randomId, after.randomId)
        assertEquals(before.identityEpoch, after.identityEpoch)
        assertEquals("hrink_test", after.username)
        db.close()
    }
}
