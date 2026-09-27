package com.notrace.messenger.group.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notrace.messenger.group.data.GroupEntity
import com.notrace.messenger.group.domain.GroupCoordinator
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.identity.data.ContactEntity
import com.notrace.messenger.identity.domain.ContactRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class GroupsListViewModel(
    private val groupRepository: GroupRepository,
    private val contactRepository: ContactRepository,
    private val coordinator: GroupCoordinator
) : ViewModel() {

    val groups: StateFlow<List<GroupEntity>> = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Candidates for a new group - unblocked contacts, since a blocked contact shouldn't be invited. */
    val availableContacts: StateFlow<List<ContactEntity>> = contactRepository.observeContacts()
        .map { list -> list.filter { !it.isBlocked } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createGroup(name: String, memberRandomIds: Set<String>) {
        if (name.isBlank() || memberRandomIds.isEmpty()) return
        coordinator.createGroup(name.trim(), memberRandomIds)
    }

    class Factory(
        private val groupRepository: GroupRepository,
        private val contactRepository: ContactRepository,
        private val coordinator: GroupCoordinator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GroupsListViewModel(groupRepository, contactRepository, coordinator) as T
    }
}
