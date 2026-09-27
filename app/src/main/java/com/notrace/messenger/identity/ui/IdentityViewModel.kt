package com.notrace.messenger.identity.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notrace.messenger.identity.data.SelfIdentityEntity
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.identity.domain.UsernameChangeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IdentityViewModel(private val repository: IdentityRepository) : ViewModel() {

    private val _selfIdentity = MutableStateFlow<SelfIdentityEntity?>(null)
    val selfIdentity: StateFlow<SelfIdentityEntity?> = _selfIdentity.asStateFlow()

    private val _usernameError = MutableStateFlow<String?>(null)
    val usernameError: StateFlow<String?> = _usernameError.asStateFlow()

    /** Ensures an identity exists (generating one on first-ever call) and loads it. */
    fun ensureIdentityLoaded() {
        viewModelScope.launch {
            _selfIdentity.value = repository.getOrCreateSelfIdentity()
        }
    }

    fun setUsername(username: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            when (repository.setUsername(username)) {
                UsernameChangeResult.Success -> {
                    _usernameError.value = null
                    _selfIdentity.value = repository.getOrCreateSelfIdentity()
                    onSuccess()
                }
                UsernameChangeResult.TooLong -> _usernameError.value = "Username is too long (max 32 characters)."
                UsernameChangeResult.InvalidCharacters -> _usernameError.value = "Only letters, numbers, and underscore are allowed."
            }
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteAccountAndWipeAllData()
            _selfIdentity.value = null
            onDone()
        }
    }

    class Factory(private val repository: IdentityRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            IdentityViewModel(repository) as T
    }
}
