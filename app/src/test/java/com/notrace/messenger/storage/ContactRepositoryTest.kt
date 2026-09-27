package com.notrace.messenger.storage

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.identity.data.VerificationStatus
import com.notrace.messenger.identity.domain.AddContactResult
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.storage.db.NoTraceDatabase
import com.notrace.messenger.storage.wipe.StorageWipeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun buildRepos(): Pair<IdentityRepository, ContactRepository> {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val identityRepo = IdentityRepository(db.selfIdentityDao(), StorageWipeManager(context))
        val contactRepo = ContactRepository(db.contactDao(), identityRepo)
        return identityRepo to contactRepo
    }

    @Test
    fun addContact_rejectsInvalidId() = runBlocking {
        val (_, contacts) = buildRepos()
        val result = contacts.addContact("123", null)
        assertEquals(AddContactResult.InvalidId, result)
    }

    @Test
    fun addContact_rejectsSelf() = runBlocking {
        val (identity, contacts) = buildRepos()
        val self = identity.getOrCreateSelfIdentity()

        val result = contacts.addContact(self.randomId, null)

        assertEquals(AddContactResult.CannotAddSelf, result)
    }

    @Test
    fun addContact_succeedsAsUnverifiedAndUnblocked() = runBlocking {
        val (_, contacts) = buildRepos()

        val result = contacts.addContact("111122223333", "Friend")

        assertEquals(AddContactResult.Success, result)
        val stored = contacts.getContact("111122223333")
        assertEquals(VerificationStatus.UNVERIFIED, stored?.verificationStatus)
        assertEquals(false, stored?.isBlocked)
        assertEquals("Friend", stored?.localNickname)
    }

    @Test
    fun addContact_rejectsDuplicate() = runBlocking {
        val (_, contacts) = buildRepos()
        contacts.addContact("111122223333", null)

        val result = contacts.addContact("111122223333", null)

        assertEquals(AddContactResult.AlreadyExists, result)
    }

    @Test
    fun blockingDoesNotClearVerification() = runBlocking {
        val (_, contacts) = buildRepos()
        contacts.addContact("111122223333", null)
        contacts.setVerified("111122223333", true)

        contacts.setBlocked("111122223333", true)
        val stored = contacts.getContact("111122223333")

        assertTrue(stored!!.isBlocked)
        assertEquals(VerificationStatus.VERIFIED, stored.verificationStatus)
    }

    @Test
    fun removeContact_deletesIt() = runBlocking {
        val (_, contacts) = buildRepos()
        contacts.addContact("111122223333", null)

        contacts.removeContact("111122223333")

        assertNull(contacts.getContact("111122223333"))
    }
}
