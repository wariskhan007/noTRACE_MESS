package com.notrace.messenger.selfdestruct

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.crypto.data.DeliveryState
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.storage.db.NoTraceDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageExpiryManagerTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun getExpired_returnsOnlyPastDueMessagesWithATimerSet() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val dao = db.messageDao()
        val now = System.currentTimeMillis()

        dao.insert(baseMessage("no timer", expiresAt = null))
        dao.insert(baseMessage("expired", expiresAt = now - 1000))
        dao.insert(baseMessage("not yet", expiresAt = now + 60_000))

        val expired = dao.getExpired(now)

        assertEquals(1, expired.size)
        assertEquals("expired", expired.first().body)
        db.close()
    }

    @Test
    fun deletingExpiredMessage_removesItFromConversation() = runBlocking {
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-pass".toCharArray())
        val dao = db.messageDao()
        val now = System.currentTimeMillis()

        val id = dao.insert(baseMessage("expired", expiresAt = now - 1000))
        dao.delete(id)

        val remaining = dao.getExpired(now)
        assertEquals(0, remaining.size)
        db.close()
    }

    private fun baseMessage(body: String, expiresAt: Long?) = MessageEntity(
        contactRandomId = "111122223333",
        direction = MessageDirection.OUTGOING,
        body = body,
        sentOrReceivedAtEpochMillis = System.currentTimeMillis(),
        deliveryState = DeliveryState.SENT,
        expiresAtEpochMillis = expiresAt
    )
}
