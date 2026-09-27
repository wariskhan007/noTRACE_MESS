package com.notrace.messenger.group

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.storage.db.NoTraceDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure data/membership-layer tests - no libsignal group crypto touched
 * here (that needs a real device, same constraint as every libsignal-
 * touching test since Phase 4). This covers the part that's fully
 * testable on the host JVM: group creation, admin assignment, member
 * add/remove bookkeeping, and full local deletion.
 */
@RunWith(RobolectricTestRunner::class)
class GroupRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun buildRepo(): GroupRepository {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        return GroupRepository(db.groupDao(), db.groupMemberDao())
    }

    @Test
    fun createGroup_makesCreatorTheSoleAdmin() = runBlocking {
        val repo = buildRepo()
        val groupId = repo.createGroup("Test Group", "111111111111", setOf("222222222222", "333333333333"))

        val members = repo.getMembers(groupId)
        assertEquals(3, members.size)
        assertTrue(repo.isAdmin(groupId, "111111111111"))
        assertTrue(!repo.isAdmin(groupId, "222222222222"))
        assertTrue(!repo.isAdmin(groupId, "333333333333"))
    }

    @Test
    fun createGroup_excludesCreatorFromMemberListDuplication() = runBlocking {
        val repo = buildRepo()
        // Creator accidentally included in the member set too - should still end up as exactly one row.
        val groupId = repo.createGroup("Test", "111111111111", setOf("111111111111", "222222222222"))

        val members = repo.getMembers(groupId)
        assertEquals(2, members.size)
    }

    @Test
    fun removeMemberLocally_removesOnlyThatMember() = runBlocking {
        val repo = buildRepo()
        val groupId = repo.createGroup("Test", "111111111111", setOf("222222222222", "333333333333"))

        repo.removeMemberLocally(groupId, "222222222222")

        val members = repo.getMembers(groupId)
        assertEquals(2, members.size)
        assertNull(members.find { it.memberRandomId == "222222222222" })
    }

    @Test
    fun deleteGroupLocally_removesGroupAndAllMemberships() = runBlocking {
        val repo = buildRepo()
        val groupId = repo.createGroup("Test", "111111111111", setOf("222222222222"))

        repo.deleteGroupLocally(groupId)

        assertNull(repo.getGroup(groupId))
        assertEquals(0, repo.getMembers(groupId).size)
    }

    @Test
    fun setDisappearingMessageSeconds_updatesGroup() = runBlocking {
        val repo = buildRepo()
        val groupId = repo.createGroup("Test", "111111111111", setOf("222222222222"))

        repo.setDisappearingMessageSeconds(groupId, 3600L)

        assertEquals(3600L, repo.getGroup(groupId)?.disappearingMessageSeconds)
    }

    @Test
    fun addMemberLocally_defaultsToNonAdmin() = runBlocking {
        val repo = buildRepo()
        val groupId = repo.createGroup("Test", "111111111111", emptySet())

        repo.addMemberLocally(groupId, "999999999999")

        assertTrue(!repo.isAdmin(groupId, "999999999999"))
    }
}
