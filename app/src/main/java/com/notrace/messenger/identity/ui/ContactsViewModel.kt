package com.notrace.messenger.identity.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notrace.messenger.identity.data.ContactEntity
import com.notrace.messenger.identity.domain.AddContactResult
import com.notrace.messenger.identity.domain.ContactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(private val repository: ContactRepository) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> = repository.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _addContactError = MutableStateFlow<String?>(null)
    val addContactError: StateFlow<String?> = _addContactError.asStateFlow()

    fun addContact(rawId: String, nickname: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            when (repository.addContact(rawId, nickname)) {
                AddContactResult.Success -> {
                    _addContactError.value = null
                    onSuccess()
                }
                AddContactResult.InvalidId -> _addContactError.value = "That doesn't look like a valid 12-digit ID."
                AddContactResult.CannotAddSelf -> _addContactError.value = "That's your own ID."
                AddContactResult.AlreadyExists -> _addContactError.value = "Already in your contacts."
            }
        }
    }

    fun clearAddContactError() {
        _addContactError.value = null
    }

    fun setVerified(randomId: String, verified: Boolean) {
        viewModelScope.launch { repository.setVerified(randomId, verified) }
    }

    fun setBlocked(randomId: String, blocked: Boolean) {
        viewModelScope.launch { repository.setBlocked(randomId, blocked) }
    }

    fun removeContact(randomId: String) {
        viewModelScope.launch { repository.removeContact(randomId) }
    }

    class Factory(private val repository: ContactRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ContactsViewModel(repository) as T
    }
}
