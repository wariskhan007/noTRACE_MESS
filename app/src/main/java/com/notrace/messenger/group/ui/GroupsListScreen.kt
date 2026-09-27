package com.notrace.messenger.group.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.group.domain.GroupCoordinator
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.identity.domain.RandomIdGenerator

@Composable
fun GroupsListScreen(
    groupRepository: GroupRepository,
    contactRepository: ContactRepository,
    coordinator: GroupCoordinator,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: GroupsListViewModel = viewModel(factory = GroupsListViewModel.Factory(groupRepository, contactRepository, coordinator))
) {
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.availableContacts.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Groups") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New group")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No groups yet. Tap + to create one.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(groups, key = { it.id }) { group ->
                    ListItem(
                        headlineContent = { Text(group.name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { onOpenGroup(group.id) }) { Text("Open") }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            contacts = contacts,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, memberIds ->
                viewModel.createGroup(name, memberIds)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CreateGroupDialog(
    contacts: List<com.notrace.messenger.identity.data.ContactEntity>,
    onDismiss: () -> Unit,
    onCreate: (name: String, memberIds: Set<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Group name") })
                Text("Members:", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (contacts.isEmpty()) {
                    Text("No contacts yet - add some first.")
                }
                contacts.forEach { contact ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = selected.value.contains(contact.randomId),
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + contact.randomId else selected.value - contact.randomId
                            }
                        )
                        Text(
                            contact.localNickname ?: RandomIdGenerator.format(contact.randomId),
                            modifier = Modifier.padding(start = 40.dp, top = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, selected.value) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
