package com.notrace.messenger.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notrace.messenger.attachment.data.AttachmentDao
import com.notrace.messenger.attachment.data.AttachmentEntity
import com.notrace.messenger.crypto.data.CryptoSelfIdentityDao
import com.notrace.messenger.crypto.data.CryptoSelfIdentityEntity
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.crypto.data.OneTimePreKeyDao
import com.notrace.messenger.crypto.data.OneTimePreKeyEntity
import com.notrace.messenger.crypto.data.RemoteIdentityKeyDao
import com.notrace.messenger.crypto.data.RemoteIdentityKeyEntity
import com.notrace.messenger.crypto.data.SessionDao
import com.notrace.messenger.crypto.data.SessionEntity
import com.notrace.messenger.crypto.data.SignedPreKeyDao
import com.notrace.messenger.crypto.data.SignedPreKeyEntity
import com.notrace.messenger.group.data.GroupDao
import com.notrace.messenger.group.data.GroupEntity
import com.notrace.messenger.group.data.GroupMemberDao
import com.notrace.messenger.group.data.GroupMemberEntity
import com.notrace.messenger.group.data.GroupSenderKeyDao
import com.notrace.messenger.group.data.GroupSenderKeyEntity
import com.notrace.messenger.identity.data.ContactDao
import com.notrace.messenger.identity.data.SelfIdentityDao
import com.notrace.messenger.storage.keys.DatabaseKeyManager
import net.sqlcipher.database.SupportFactory

/**
 * Encrypted-at-rest Room database.
 *
 * The SQLite file on disk is encrypted end-to-end by SQLCipher (via
 * SupportFactory) using a passphrase that itself never exists in
 * plaintext outside process memory (see DatabaseKeyManager). Room only
 * ever sees the decrypted logical database through the SupportSQLite
 * interface — no application code (including future phases) needs to
 * know encryption is happening underneath.
 *
 * CURRENT_VERSION history:
 *   v1 (Phase 2): storage_metadata only.
 *   v2 (Phase 3): + self_identity, + contacts.
 *   v3 (Phase 4): + crypto_self_identity, one_time_prekeys,
 *                 signed_prekeys, remote_identity_keys, sessions, messages.
 *   v4 (Phase 7): + attachments; messages gains attachmentId.
 *   v5 (Phase 10): + groups, group_members, group_sender_keys;
 *                  messages gains groupId.
 *   v6 (Phase 11): contacts gains disappearingMessageSeconds.
 *
 * Every future schema change MUST:
 *   1. Bump CURRENT_VERSION,
 *   2. Add a Migration(oldVersion, newVersion) to ALL_MIGRATIONS below,
 *   3. Never use fallbackToDestructiveMigration() — a destructive
 *      "migration" would silently delete message history, which is
 *      exactly the kind of silent data loss the plan's process forbids
 *      (Section 22 "no silent placeholders/fake features").
 */
@Database(
    entities = [
        StorageMetadataEntity::class,
        com.notrace.messenger.identity.data.SelfIdentityEntity::class,
        com.notrace.messenger.identity.data.ContactEntity::class,
        CryptoSelfIdentityEntity::class,
        OneTimePreKeyEntity::class,
        SignedPreKeyEntity::class,
        RemoteIdentityKeyEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupSenderKeyEntity::class
    ],
    version = NoTraceDatabase.CURRENT_VERSION,
    exportSchema = true
)
abstract class NoTraceDatabase : RoomDatabase() {

    abstract fun storageMetadataDao(): StorageMetadataDao
    abstract fun selfIdentityDao(): SelfIdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun cryptoSelfIdentityDao(): CryptoSelfIdentityDao
    abstract fun oneTimePreKeyDao(): OneTimePreKeyDao
    abstract fun signedPreKeyDao(): SignedPreKeyDao
    abstract fun remoteIdentityKeyDao(): RemoteIdentityKeyDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun groupSenderKeyDao(): GroupSenderKeyDao

    companion object {
        const val CURRENT_VERSION = 6
        private const val DB_FILE_NAME = "notrace_encrypted.db"


        /**
         * v1 -> v2 (Phase 3): adds self_identity and contacts tables.
         * Purely additive — no existing storage_metadata rows are touched,
         * so this migration cannot lose data.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `self_identity` (
                        `id` INTEGER NOT NULL,
                        `randomId` TEXT NOT NULL,
                        `username` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `identityEpoch` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contacts` (
                        `randomId` TEXT NOT NULL,
                        `localNickname` TEXT,
                        `lastKnownUsername` TEXT,
                        `verificationStatus` TEXT NOT NULL,
                        `isBlocked` INTEGER NOT NULL,
                        `addedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`randomId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 -> v3 (Phase 4): adds the Signal Protocol store tables and
         * the message log. All new tables, all additive.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `crypto_self_identity` (
                        `id` INTEGER NOT NULL,
                        `identityKeyPairBytes` BLOB NOT NULL,
                        `registrationId` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `one_time_prekeys` (
                        `preKeyId` INTEGER NOT NULL,
                        `recordBytes` BLOB NOT NULL,
                        `issued` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`preKeyId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `signed_prekeys` (
                        `signedPreKeyId` INTEGER NOT NULL,
                        `recordBytes` BLOB NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`signedPreKeyId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_identity_keys` (
                        `contactRandomId` TEXT NOT NULL,
                        `identityKeyBytes` BLOB NOT NULL,
                        `firstSeenAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`contactRandomId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `contactRandomId` TEXT NOT NULL,
                        `sessionRecordBytes` BLOB NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`contactRandomId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` INTEGER NOT NULL,
                        `contactRandomId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `sentOrReceivedAtEpochMillis` INTEGER NOT NULL,
                        `deliveryState` TEXT NOT NULL,
                        `expiresAtEpochMillis` INTEGER,
                        PRIMARY KEY(`id` AUTOINCREMENT)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 -> v4 (Phase 7): adds the attachments table and a nullable
         * attachmentId column on messages (SQLite ALTER TABLE ADD COLUMN
         * is additive/non-destructive - existing rows get NULL, meaning
         * "not an attachment", exactly matching their pre-Phase-7 meaning).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachments` (
                        `id` TEXT NOT NULL,
                        `contactRandomId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256Hex` TEXT NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `completedChunks` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `localFileName` TEXT,
                        `thumbnailFileName` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `expiresAtEpochMillis` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentId` TEXT")
            }
        }

        /**
         * v4 -> v5 (Phase 10): adds groups, group_members,
         * group_sender_keys, and a nullable groupId on messages. All
         * additive - existing 1:1 messages get groupId = NULL, exactly
         * matching their pre-Phase-10 meaning (not a group message).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `creatorRandomId` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `disappearingMessageSeconds` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_members` (
                        `groupId` TEXT NOT NULL,
                        `memberRandomId` TEXT NOT NULL,
                        `isAdmin` INTEGER NOT NULL,
                        `joinedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `memberRandomId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_sender_keys` (
                        `groupId` TEXT NOT NULL,
                        `senderRandomId` TEXT NOT NULL,
                        `recordBytes` BLOB NOT NULL,
                        PRIMARY KEY(`groupId`, `senderRandomId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `groupId` TEXT")
            }
        }

        /** v5 -> v6 (Phase 11): adds the per-1:1-conversation disappearing-message timer setting. Additive; existing contacts get NULL = off. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `disappearingMessageSeconds` INTEGER")
            }
        }

        // Registry of all migrations this app has ever needed. Append new
        // entries here as schema evolves — never remove old entries, since
        // a user could be updating from any past version.
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        @Volatile private var instance: NoTraceDatabase? = null

        fun getInstance(context: Context): NoTraceDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): NoTraceDatabase {
            val keyManager = DatabaseKeyManager(context)
            val passphrase = keyManager.getOrCreatePassphrase()
            val factory = SupportFactory(
                net.sqlcipher.database.SQLiteDatabase.getBytes(passphrase)
            )
            // Zero the CharArray copy now that SQLCipher has its own byte[] copy.
            passphrase.fill('0')

            return Room.databaseBuilder(context, NoTraceDatabase::class.java, DB_FILE_NAME)
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }

        /**
         * Test-only: an in-memory (still SQLCipher-backed) instance so unit
         * tests don't touch real device storage or the real Keystore.
         */
        fun buildInMemoryForTest(context: Context, passphrase: CharArray): NoTraceDatabase {
            val factory = SupportFactory(
                net.sqlcipher.database.SQLiteDatabase.getBytes(passphrase)
            )
            return Room.inMemoryDatabaseBuilder(context, NoTraceDatabase::class.java)
                .openHelperFactory(factory)
                .allowMainThreadQueries()
                .build()
        }

        /** Absolute path of the DB file, needed by StorageWipeManager to delete it. */
        fun databaseFile(context: Context) = context.getDatabasePath(DB_FILE_NAME)

        /**
         * Closes and drops the cached singleton instance, if one exists.
         * Must be called before deleting the underlying DB file (StorageWipeManager)
         * so there's never an open connection pointing at a now-deleted file.
         */
        @Synchronized
        fun closeIfOpen() {
            instance?.close()
            instance = null
        }
    }
}
