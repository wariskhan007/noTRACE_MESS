package com.notrace.messenger.selfdestruct.domain

import com.notrace.messenger.attachment.data.AttachmentDao
import com.notrace.messenger.crypto.data.MessageDao
import com.notrace.messenger.crypto.data.SessionDao
import com.notrace.messenger.storage.files.EncryptedFileStore

/**
 * "Conversation destruction" (plan Section 9): destroys local
 * ciphertext, local attachments, conversation-specific keys,
 * thumbnails, metadata, and delivery state for ONE selected
 * conversation - stronger than just clearing message history, because
 * it also destroys the 1:1 Signal Protocol SESSION (the Double Ratchet
 * state), matching the plan's explicit "conversation-specific keys"
 * wording. Messaging that contact again afterward starts a brand-new
 * session from scratch (a fresh bundle exchange) - deliberate, not a
 * bug: an old ratchet chain is exactly the kind of "conversation key"
 * this feature exists to destroy.
 *
 * Does NOT touch: the contact's identity/verification status (Phase 3)
 * or randomId - "destroying a conversation" means its content and
 * session history, not un-knowing who the contact is (that's Phase
 * 3's separate "remove contact" action).
 *
 * For a group, there is no equivalent "just this conversation's
 * session" concept (sender keys are shared infrastructure for the
 * whole group, not a 1:1-style bilateral session) - destroying a
 * group's conversation here only clears messages/attachments, leaving
 * membership and sender-key state intact (you're clearing history,
 * not leaving). Leaving/dissolving (Phase 10's GroupCoordinator) is
 * the action that also tears down membership and keys.
 */
class ConversationDestructionManager(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val sessionDao: SessionDao,
    private val encryptedFileStore: EncryptedFileStore,
    private val expiryManager: MessageExpiryManager
) {
    /** Destroys a 1:1 conversation: messages, attachments/thumbnails, and the session (conversation-specific keys). */
    suspend fun destroyConversation(contactRandomId: String) {
        val attachmentIds = messageDao.getAttachmentIdsForContact(contactRandomId)
        attachmentIds.forEach { expiryManager.deleteAttachmentFilesAndRow(it) }
        messageDao.deleteAllForContact(contactRandomId)
        sessionDao.delete(contactRandomId)
    }

    /** Destroys a group's message history only - membership/sender-keys survive (see class doc). */
    suspend fun destroyGroupConversation(groupId: String) {
        val attachmentIds = messageDao.getAttachmentIdsForGroup(groupId)
        attachmentIds.forEach { expiryManager.deleteAttachmentFilesAndRow(it) }
        messageDao.deleteAllForGroup(groupId)
    }
}
