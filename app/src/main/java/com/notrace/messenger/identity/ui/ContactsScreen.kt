package com.notrace.messenger.identity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.identity.data.ContactEntity
import com.notrace.messenger.identity.data.VerificationStatus
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.identity.domain.RandomIdGenerator

/**
 * Contact list — the app's main/home screen post-setup. Replaces
 * Phase 1's PlaceholderConversationsScreen. Actual conversations/chat
 * screens arrive in Phase 4-6; for now, tapping a contact does nothing
 * beyond the verify/block actions this phase implements.
 */
@Composable
fun ContactsScreen(
    repository: ContactRepository,
    onOpenSettings: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenGroups: () -> Unit,
    viewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory(repository))
) {
    val contacts by viewModel.contacts.collectAsState()
    val addError by viewModel.addContactError.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    TextButton(onClick = onOpenGroups) { Text("Groups") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No contacts yet. Tap + to add someone by their ID.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(contacts, key = { it.randomId }) { contact ->
                    ContactRow(
                        contact = contact,
                        onToggleVerified = { viewModel.setVerified(contact.randomId, it) },
                        onToggleBlocked = { viewModel.setBlocked(contact.randomId, it) },
                        onRemove = { viewModel.removeContact(contact.randomId) },
                        onOpenChat = { onOpenChat(contact.randomId) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            error = addError,
            onDismiss = {
                showAddDialog = false
                viewModel.clearAddContactError()
            },
            onAdd = { id, nickname ->
                viewModel.addContact(id, nickname) { showAddDialog = false }
            }
        )
    }
}

@Composable
private fun ContactRow(
    contact: ContactEntity,
    onToggleVerified: (Boolean) -> Unit,
    onToggleBlocked: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onOpenChat: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(contact.localNickname ?: RandomIdGenerator.format(contact.randomId))
        },
        supportingContent = {
            val verifiedLabel = when (contact.verificationStatus) {
                VerificationStatus.VERIFIED -> "Verified"
                VerificationStatus.NEEDS_REVERIFICATION -> "Needs re-verification"
                VerificationStatus.UNVERIFIED -> "Unverified"
            }
            val blockedLabel = if (contact.isBlocked) " · Blocked" else ""
            Text("$verifiedLabel$blockedLabel")
        },
        trailingContent = {
            Row {
                TextButton(onClick = onOpenChat) { Text("Chat") }
                TextButton(onClick = {
                    onToggleVerified(contact.verificationStatus != VerificationStatus.VERIFIED)
                }) {
                    Text(if (contact.verificationStatus == VerificationStatus.VERIFIED) "Unverify" else "Verify")
                }
                TextButton(onClick = { onToggleBlocked(!contact.isBlocked) }) {
                    Text(if (contact.isBlocked) "Unblock" else "Block")
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    )
}

@Composable
private fun AddContactDialog(
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (id: String, nickname: String?) -> Unit
) {
    var idInput by remember { mutableStateOf("") }
    var nicknameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = idInput,
                    onValueChange = { idInput = it },
                    label = { Text("Their 12-digit ID") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text("Nickname (optional, only visible to you)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(idInput, nicknameInput.ifBlank { null }) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
