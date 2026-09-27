package com.notrace.messenger

import android.app.Application
import com.notrace.messenger.attachment.domain.AttachmentTransferManager
import com.notrace.messenger.call.domain.CallManager
import com.notrace.messenger.crypto.domain.CryptoIdentityManager
import com.notrace.messenger.crypto.domain.MessagingRepository
import com.notrace.messenger.crypto.domain.NoTraceProtocolStore
import com.notrace.messenger.group.domain.GroupCoordinator
import com.notrace.messenger.group.domain.GroupCryptoManager
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.group.domain.GroupSenderKeyStore
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.network.domain.P2PSessionCoordinator
import com.notrace.messenger.network.reliability.NetworkMonitor
import com.notrace.messenger.network.signaling.SignalingClient
import com.notrace.messenger.network.signaling.SignalingConnectionState
import com.notrace.messenger.network.signaling.SignalingSettingsStore
import com.notrace.messenger.network.webrtc.CallMediaConnectionManager
import com.notrace.messenger.network.webrtc.P2PConnectionManager
import com.notrace.messenger.selfdestruct.domain.ConversationDestructionManager
import com.notrace.messenger.selfdestruct.domain.MessageExpiryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.notrace.messenger.storage.db.NoTraceDatabase
import com.notrace.messenger.storage.wipe.StorageWipeManager
import net.sqlcipher.database.SQLiteDatabase

/**
 * Phase 2: initializes the encrypted database layer.
 *
 * SQLiteDatabase.loadLibs() loads SQLCipher's native library once at
 * process start, before anything tries to open the database. Actual
 * database *opening* (and passphrase retrieval) is lazy — it happens on
 * first access via NoTraceDatabase.getInstance(), not here — so a cold
 * start doesn't pay the Keystore/DB-open cost before it's needed (plan
 * Section 20 performance concern noted in Phase 1).
 *
 * Nothing else initializes eagerly here, including Phase 5's signaling
 * client - it's lazy in AppContainer, built only when a chat is first
 * opened, for the same cold-start reason.
 */
class NoTraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this)

        // Phase 11: the disappearing-message sweep must run regardless
        // of which screen (if any) the user has open - a message can
        // expire while sitting on the contacts list, not just inside
        // its own chat. This is a deliberate exception to this file's
        // otherwise-consistent "stay lazy, keep cold start fast"
        // discipline (Phase 1/2/5's stated reasoning) - the cost here
        // is one lightweight DB query every 15s, not a network
        // connection or key generation, so it was judged worth it
        // rather than leaving the feature silently not actually work
        // unless a chat happened to be open.
        container.messageExpiryManager.start()

        // Phase 12: registering the network callback is cheap (no
        // active socket of its own, just an OS-level registration), so
        // this also runs eagerly rather than waiting for first chat use.
        container.networkMonitor.start()

        // Cheap to call eagerly - just registers a request with
        // WorkManager, doesn't do any actual work itself (see
        // MaintenanceScheduler's doc).
        com.notrace.messenger.maintenance.MaintenanceScheduler.schedule(this)
    }

    /**
     * Minimal manual dependency container (Phase 3). No DI framework has
     * been chosen for this project yet; introducing one (Hilt/Koin) is a
     * standalone decision better made once there are enough moving parts
     * to justify it, rather than bundled quietly into this phase. This
     * container is intentionally small and swappable for that later.
     */
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(app: Application) {
    private val database: NoTraceDatabase by lazy { NoTraceDatabase.getInstance(app) }
    private val wipeManager: StorageWipeManager by lazy { StorageWipeManager(app) }

    val identityRepository: IdentityRepository by lazy {
        IdentityRepository(database.selfIdentityDao(), wipeManager)
    }
    val contactRepository: ContactRepository by lazy {
        ContactRepository(database.contactDao(), identityRepository)
    }

    private val cryptoIdentityManager: CryptoIdentityManager by lazy {
        CryptoIdentityManager(database.cryptoSelfIdentityDao(), database.oneTimePreKeyDao(), database.signedPreKeyDao())
    }

    /**
     * The protocol store needs the identity key pair synchronously at
     * construction (libsignal's SignalProtocolStore.getIdentityKeyPair()
     * has no suspend variant), so this is built lazily via runBlocking
     * on first access rather than at app startup - keeping cold start
     * fast (same reasoning as the DB itself), while still only ever
     * blocking once per process, on whichever thread first touches
     * messagingRepository.
     */
    private val protocolStore: NoTraceProtocolStore by lazy {
        val identityKeyPair = kotlinx.coroutines.runBlocking { cryptoIdentityManager.getOrCreateIdentityKeyPair() }
        val registrationId = kotlinx.coroutines.runBlocking { cryptoIdentityManager.getLocalRegistrationId() }
        NoTraceProtocolStore(
            identityKeyPair,
            registrationId,
            database.oneTimePreKeyDao(),
            database.signedPreKeyDao(),
            database.remoteIdentityKeyDao(),
            database.sessionDao()
        )
    }

    val messagingRepository: MessagingRepository by lazy {
        MessagingRepository(
            cryptoIdentityManager,
            protocolStore,
            database.sessionDao(),
            database.messageDao(),
            database.contactDao()
        )
    }

    val signalingSettingsStore: SignalingSettingsStore by lazy { SignalingSettingsStore(app) }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Built lazily, once, on first access - same reasoning as
     * protocolStore: don't pay connection/key-load cost until a chat
     * is actually opened. If no signaling server is configured yet
     * (fresh install, Hrink hasn't deployed one), connectionState just
     * stays FAILED/DISCONNECTED and ChatScreen falls back to Phase 4's
     * manual bundle/ciphertext paste - never crashes on a blank URL.
     */
    val signalingClient: SignalingClient by lazy {
        val identityKeyPair = kotlinx.coroutines.runBlocking { cryptoIdentityManager.getOrCreateIdentityKeyPair() }
        val selfIdentity = kotlinx.coroutines.runBlocking { identityRepository.getOrCreateSelfIdentity() }
        SignalingClient(
            serverUrl = signalingSettingsStore.getServerUrl(),
            myRandomId = selfIdentity.randomId,
            identityKeyPair = identityKeyPair,
            scope = backgroundScope
        )
    }

    /**
     * Started eagerly (NoTraceApplication.onCreate) - registering a
     * network callback is cheap (no active socket/loop of its own), so
     * this doesn't meaningfully conflict with the lazy-cold-start
     * discipline used elsewhere. Reconnects signaling immediately on
     * network restore rather than waiting for the next backoff attempt.
     */
    val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(app).also { monitor ->
            monitor.networkAvailable.onEach {
                if (signalingSettingsStore.getServerUrl().isNotBlank() &&
                    signalingClient.connectionState.value != SignalingConnectionState.READY &&
                    signalingClient.connectionState.value != SignalingConnectionState.CONNECTING
                ) {
                    signalingClient.connect()
                }
            }.launchIn(backgroundScope)
        }
    }

    val p2pSessionCoordinator: P2PSessionCoordinator by lazy {
        P2PSessionCoordinator(
            signalingClient = signalingClient,
            p2pFactory = { _ -> P2PConnectionManager(app) },
            messagingRepository = messagingRepository,
            scope = backgroundScope
        )
    }

    private val encryptedFileStore by lazy {
        com.notrace.messenger.storage.files.EncryptedFileStore(app)
    }

    /** Enforces per-message disappearing timers (Phase 11) - started from NoTraceApplication.onCreate, not lazily on first UI access, so it runs the whole time the app is alive. */
    val messageExpiryManager: MessageExpiryManager by lazy {
        MessageExpiryManager(database.messageDao(), database.attachmentDao(), encryptedFileStore, backgroundScope)
    }

    val conversationDestructionManager: ConversationDestructionManager by lazy {
        ConversationDestructionManager(database.messageDao(), database.attachmentDao(), database.sessionDao(), encryptedFileStore, messageExpiryManager)
    }

    /**
     * Requires a real coordinator (i.e. a configured signaling server) -
     * unlike text messages, attachments have no manual copy-paste
     * fallback (chunked binary data isn't practical to hand-copy), so
     * the "Attach" button in ChatScreen only appears when
     * automaticModeAvailable is true. See AttachmentTransferManager's
     * class doc for other scoped-out limitations this phase.
     */
    val attachmentTransferManager: AttachmentTransferManager by lazy {
        AttachmentTransferManager(
            context = app,
            coordinator = p2pSessionCoordinator,
            attachmentDao = database.attachmentDao(),
            messageDao = database.messageDao(),
            encryptedFileStore = encryptedFileStore,
            scope = backgroundScope
        )
    }

    /**
     * App-wide (not per-contact) - there is only ever one active call,
     * and the incoming-call UI must be reachable no matter which
     * screen the user is currently on (see CallScreen's doc). Built
     * lazily on first access to the same signalingClient instance
     * P2PSessionCoordinator already uses, so both share one connection
     * to the server rather than opening two.
     */
    val callManager: CallManager by lazy {
        CallManager(
            context = app,
            signalingClient = signalingClient,
            callConnectionFactory = { withVideo -> CallMediaConnectionManager(app, withVideo = withVideo) },
            messageDao = database.messageDao(),
            scope = backgroundScope
        )
    }

    val groupRepository: GroupRepository by lazy {
        GroupRepository(database.groupDao(), database.groupMemberDao())
    }

    /**
     * Cached after first access (Kotlin `by lazy` semantics) - safe to
     * read from a composable's body without blocking on every
     * recomposition, unlike calling identityRepository directly with
     * runBlocking inline would be. The one-time blocking cost itself is
     * the same acceptable tradeoff already used for signalingClient/
     * callManager/groupCoordinator below.
     */
    val ownRandomId: String by lazy {
        kotlinx.coroutines.runBlocking { identityRepository.getOrCreateSelfIdentity() }.randomId
    }

    private val groupSenderKeyStore: GroupSenderKeyStore by lazy {
        GroupSenderKeyStore(database.groupSenderKeyDao())
    }

    private val groupCryptoManager: GroupCryptoManager by lazy {
        GroupCryptoManager(database.groupSenderKeyDao(), groupSenderKeyStore)
    }

    /**
     * Like attachments, groups require a configured signaling server -
     * fanning a group message out to N members pairwise has no sensible
     * manual copy-paste equivalent. See GroupCoordinator's class doc
     * for the wire protocol.
     */
    val groupCoordinator: GroupCoordinator by lazy {
        GroupCoordinator(
            ownRandomId = ownRandomId,
            groupRepository = groupRepository,
            cryptoManager = groupCryptoManager,
            p2pSessionCoordinator = p2pSessionCoordinator,
            messageDao = database.messageDao(),
            messageExpiryManager = messageExpiryManager,
            scope = backgroundScope
        )
    }
}

/** Convenience accessor used by ViewModels/repositories in later phases. */
fun Application.noTraceDatabase(): NoTraceDatabase = NoTraceDatabase.getInstance(this)
