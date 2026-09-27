package com.notrace.messenger.group.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notrace.messenger.crypto.data.MessageEntity
import com.notrace.messenger.group.data.GroupEntity
import com.notrace.messenger.group.data.GroupMemberEntity
import com.notrace.messenger.group.domain.GroupCoordinator
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.selfdestruct.domain.ConversationDestructionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupChatViewModel(
    private val groupId: String,
    private val ownRandomId: String,
    private val groupRepository: GroupRepository,
    private val coordinator: GroupCoordinator,
    private val destructionManager: ConversationDestructionManager
) : ViewModel() {

    val group: StateFlow<GroupEntity?> = groupRepository.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val members: StateFlow<List<GroupMemberEntity>> = groupRepository.observeMembers(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messages: StateFlow<List<MessageEntity>> = coordinator.observeGroupConversation(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAdmin = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin

    init {
        viewModelScope.launch { _isAdmin.value = groupRepository.isAdmin(groupId, ownRandomId) }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        coordinator.sendGroupText(groupId, text.trim())
    }

    fun addMember(memberRandomId: String) {
        coordinator.addMember(groupId, memberRandomId)
    }

    fun removeMember(memberRandomId: String) {
        coordinator.removeMember(groupId, memberRandomId)
    }

    fun leaveGroup(onDone: () -> Unit) {
        coordinator.leaveGroup(groupId)
        onDone()
    }

    fun dissolveGroup(onDone: () -> Unit) {
        coordinator.dissolveGroup(groupId)
        onDone()
    }

    /** Admin-only - enforced inside GroupCoordinator itself, not just here (a forged UI action still can't bypass it). */
    fun setDisappearingTimer(seconds: Long?) {
        coordinator.setDisappearingMessageSeconds(groupId, seconds)
    }

    /** Clears this device's local message/attachment history for the group - membership and sender keys are untouched (see ConversationDestructionManager's doc). */
    fun destroyConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            destructionManager.destroyGroupConversation(groupId)
            onDone()
        }
    }

    class Factory(
        private val groupId: String,
        private val ownRandomId: String,
        private val groupRepository: GroupRepository,
        private val coordinator: GroupCoordinator,
        private val destructionManager: ConversationDestructionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GroupChatViewModel(groupId, ownRandomId, groupRepository, coordinator, destructionManager) as T
    }
}
