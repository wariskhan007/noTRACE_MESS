package com.notrace.messenger.storage

import androidx.test.core.app.ApplicationProvider
import com.notrace.messenger.storage.db.NoTraceDatabase
import com.notrace.messenger.storage.db.StorageMetadataEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageMetadataDaoTest {

    @Test
    fun insertAndReadBack_roundTrips() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = NoTraceDatabase.buildInMemoryForTest(context, "test-passphrase".toCharArray())
        val dao = db.storageMetadataDao()

        assertNull(dao.get())

        val entity = StorageMetadataEntity(
            schemaVersion = NoTraceDatabase.CURRENT_VERSION,
            keyVersion = 1,
            createdAtEpochMillis = 1_700_000_000_000L
        )
        dao.upsert(entity)

        val fetched = dao.get()
        assertEquals(entity, fetched)

        db.close()
    }
}
