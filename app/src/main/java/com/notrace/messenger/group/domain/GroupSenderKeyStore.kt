package com.notrace.messenger.group.domain

import com.notrace.messenger.group.data.GroupSenderKeyDao
import com.notrace.messenger.group.data.GroupSenderKeyEntity
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore

/**
 * Room-backed implementation of libsignal's SenderKeyStore - the group
 * analogue of Phase 4's NoTraceProtocolStore (which implements the 1:1
 * SignalProtocolStore family). Keys are addressed by (groupId,
 * senderRandomId) rather than libsignal's native SenderKeyName(groupId,
 * SignalProtocolAddress) type, since this app has no concept of
 * multiple devices per identity (Phase 0 decision #8) - deviceId is
 * always 1, so it's dropped from the storage key entirely rather than
 * threaded through for no purpose.
 *
 * Same synchronous-bridge-over-suspend-DAO pattern as
 * NoTraceProtocolStore, for the same reason: libsignal's Java
 * interfaces are synchronous by design.
 *
 * HIGH RISK-OF-DRIFT NOTE: same caveat as NoTraceProtocolStore back in
 * Phase 4 - the exact SenderKeyStore method signatures for this
 * libsignal-android version could not be verified against a real
 * compile in this sandbox.
 */
class GroupSenderKeyStore(
    private val dao: GroupSenderKeyDao
) : SenderKeyStore {

    override fun storeSenderKey(sender: org.signal.libsignal.protocol.groups.SenderKeyName, record: SenderKeyRecord) {
        runBlocking {
            dao.upsert(GroupSenderKeyEntity(sender.groupId, sender.sender.name, record.serialize()))
        }
    }

    override fun loadSenderKey(sender: org.signal.libsignal.protocol.groups.SenderKeyName): SenderKeyRecord {
        return runBlocking {
            val entity = dao.get(sender.groupId, sender.sender.name)
            if (entity != null) SenderKeyRecord(entity.recordBytes) else SenderKeyRecord()
        }
    }
}
