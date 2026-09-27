package com.notrace.messenger.identity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.identity.domain.RandomIdGenerator
import com.notrace.messenger.selfdestruct.ui.restartApp

/**
 * Self-identity settings: view/copy your own ID, change username,
 * delete account (Section 10 "username changes", Section 11 "provide
 * account deletion instructions").
 */
@Composable
fun SettingsScreen(
    repository: IdentityRepository,
    signalingSettingsStore: com.notrace.messenger.network.signaling.SignalingSettingsStore,
    onBack: () -> Unit,
    viewModel: IdentityViewModel = viewModel(factory = IdentityViewModel.Factory(repository))
) {
    val selfIdentity by viewModel.selfIdentity.collectAsState()
    val usernameError by viewModel.usernameError.collectAsState()
    var usernameInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var signalingUrlInput by remember { mutableStateOf(signalingSettingsStore.getServerUrl()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.ensureIdentityLoaded() }
    LaunchedEffect(selfIdentity?.username) { usernameInput = selfIdentity?.username ?: "" }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Your ID", fontWeight = FontWeight.SemiBold)
            Text(selfIdentity?.randomId?.let { RandomIdGenerator.format(it) } ?: "—")
            Text(
                "This never changes for this install. Share it so people can add you.",
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = usernameInput,
                onValueChange = { usernameInput = it },
                label = { Text("Username") },
                isError = usernameError != null,
                supportingText = { usernameError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.setUsername(usernameInput.ifBlank { null }) },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Save username")
            }

            Text(
                "Signaling server (Phase 5)",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 32.dp)
            )
            Text(
                "wss:// URL of a deployed signaling-server (see its README) - enables automatic key exchange and P2P messaging. Leave blank to use manual copy-paste only.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = signalingUrlInput,
                onValueChange = { signalingUrlInput = it },
                label = { Text("wss://your-server.onrender.com") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { signalingSettingsStore.setServerUrl(signalingUrlInput) },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Save server URL") }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("Panic wipe", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    "Destroys everything NoTrace stores on this device: your identity, " +
                        "contacts, sessions, all message and group history, attachments, " +
                        "and every key this app holds. There is no server-side account, so " +
                        "nothing here can be recovered afterward - this cannot be undone.",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "This cannot delete: copies the other person already has, screenshots " +
                        "or recordings anyone made, files you previously saved outside " +
                        "NoTrace, or anything on a device that's already compromised. " +
                        "Deletion only ever applies to what this app itself controls.",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(onClick = { showDeleteConfirm = true }) {
                    Text("Panic wipe (delete account)")
                }
                TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Back")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Panic wipe?") },
            text = {
                Text(
                    "This immediately and permanently erases your identity, contacts, " +
                        "sessions, groups, all messages, and all attachments from this " +
                        "device, and destroys the keys protecting them. It cannot be " +
                        "undone, and it cannot reach anything outside this app - the other " +
                        "person's copy, any screenshots, or anything you've saved " +
                        "elsewhere are unaffected. The app will close immediately after."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteAccount(onDone = { restartApp(context) })
                }) { Text("Wipe now") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
