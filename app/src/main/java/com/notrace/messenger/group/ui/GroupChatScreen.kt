package com.notrace.messenger.group.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.group.domain.GroupCoordinator
import com.notrace.messenger.group.domain.GroupRepository
import com.notrace.messenger.identity.domain.RandomIdGenerator
import com.notrace.messenger.selfdestruct.domain.ConversationDestructionManager
import com.notrace.messenger.selfdestruct.domain.DisappearingTimerOption

/**
 * Group chat screen. Requires a signaling server (see this file's
 * caller in the nav host) - fanning a group message out to N members
 * has no manual copy-paste equivalent, same reasoning as attachments.
 */
@Composable
fun GroupChatScreen(
    groupId: String,
    ownRandomId: String,
    groupRepository: GroupRepository,
    coordinator: GroupCoordinator,
    destructionManager: ConversationDestructionManager,
    onBack: () -> Unit,
    viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModel.Factory(groupId, ownRandomId, groupRepository, coordinator, destructionManager)
    )
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()

    var draft by remember { mutableStateOf("") }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showDestroyConfirm by remember { mutableStateOf(false) }
    var addMemberId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "Group") },
                actions = {
                    TextButton(onClick = { showMembersDialog = true }) { Text("Members") }
                    if (isAdmin) {
                        TextButton(onClick = { showTimerDialog = true }) { Text("Timer") }
                    }
                    TextButton(onClick = { showDestroyConfirm = true }) { Text("Destroy") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(messages) { message ->
                    val label = if (message.direction == MessageDirection.OUTGOING && message.contactRandomId == ownRandomId) {
                        "You"
                    } else {
                        RandomIdGenerator.format(message.contactRandomId)
                    }
                    Text("$label: ${message.body}", modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { viewModel.sendText(draft); draft = "" }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Send")
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
        }
    }

    if (showMembersDialog) {
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            title = { Text("Members") },
            text = {
                Column {
                    members.forEach { member ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                RandomIdGenerator.format(member.memberRandomId) + if (member.isAdmin) " (admin)" else "",
                                modifier = Modifier.weight(1f)
                            )
                            if (isAdmin && member.memberRandomId != ownRandomId) {
                                TextButton(onClick = { viewModel.removeMember(member.memberRandomId) }) { Text("Remove") }
                            }
                        }
                    }
                    if (isAdmin) {
                        TextButton(onClick = { showAddMemberDialog = true }) { Text("Add member") }
                    }
                }
            },
            confirmButton = {
                if (isAdmin) {
                    TextButton(onClick = { viewModel.dissolveGroup { showMembersDialog = false } }) { Text("Dissolve group") }
                } else {
                    TextButton(onClick = { viewModel.leaveGroup { showMembersDialog = false } }) { Text("Leave group") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMembersDialog = false }) { Text("Close") }
            }
        )
    }

    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = { Text("Add member") },
            text = {
                OutlinedTextField(
                    value = addMemberId,
                    onValueChange = { addMemberId = it },
                    label = { Text("Their 12-digit ID") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addMember(RandomIdGenerator.normalize(addMemberId))
                    addMemberId = ""
                    showAddMemberDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTimerDialog) {
        val currentSeconds = group?.disappearingMessageSeconds
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    Text("Applies to the whole group going forward. Only the admin can change this.")
                    DisappearingTimerOption.entries.forEach { option ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            RadioButton(
                                selected = currentSeconds == option.seconds,
                                onClick = { viewModel.setDisappearingTimer(option.seconds); showTimerDialog = false }
                            )
                            Text(option.label, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTimerDialog = false }) { Text("Close") } }
        )
    }

    if (showDestroyConfirm) {
        AlertDialog(
            onDismissRequest = { showDestroyConfirm = false },
            title = { Text("Destroy this conversation?") },
            text = {
                Text(
                    "Permanently erases every message and attachment in this group's " +
                        "conversation on this device. This does not remove you from the " +
                        "group or affect anyone else's copy - use Leave/Dissolve in " +
                        "Members for that. This cannot be undone."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDestroyConfirm = false
                    viewModel.destroyConversation {}
                }) { Text("Destroy") }
            },
            dismissButton = { TextButton(onClick = { showDestroyConfirm = false }) { Text("Cancel") } }
        )
    }
}
