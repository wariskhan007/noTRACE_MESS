package com.notrace.messenger.crypto.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.attachment.audio.VoiceNoteRecorder
import com.notrace.messenger.attachment.domain.AttachmentTransferManager
import com.notrace.messenger.attachment.ui.AttachmentMessageRow
import com.notrace.messenger.call.domain.CallManager
import com.notrace.messenger.crypto.data.MessageDirection
import com.notrace.messenger.crypto.domain.MessagingRepository
import com.notrace.messenger.identity.domain.ContactRepository
import com.notrace.messenger.network.domain.P2PSessionCoordinator
import com.notrace.messenger.network.domain.PeerStatus
import com.notrace.messenger.selfdestruct.domain.ConversationDestructionManager
import com.notrace.messenger.selfdestruct.domain.DisappearingTimerOption
import kotlinx.coroutines.launch

/**
 * Chat screen for one contact.
 *
 * When a signaling server is configured (coordinator != null), opening
 * this screen automatically attempts bundle exchange and a WebRTC data
 * channel connection - the peerStatus line reflects real progress
 * (requesting bundle / connecting / connected / offline / failed).
 * Manual bundle/ciphertext paste (Phase 4) remains available below as
 * a fallback for when the peer is offline or no signaling server is
 * set up (Settings screen).
 */
@Composable
fun ChatScreen(
    contactRandomId: String,
    contactLabel: String,
    repository: MessagingRepository,
    coordinator: P2PSessionCoordinator?,
    attachmentManager: AttachmentTransferManager?,
    callManager: CallManager?,
    contactRepository: ContactRepository,
    destructionManager: ConversationDestructionManager,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(contactRandomId, repository, coordinator, attachmentManager, contactRepository, destructionManager)
    )
) {
    val messages by viewModel.messages.collectAsState()
    val hasSession by viewModel.hasSession.collectAsState()
    val myBundle by viewModel.myBundle.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val lastOutgoingCiphertext by viewModel.lastOutgoingCiphertext.collectAsState()
    val peerStatus by viewModel.peerStatus.collectAsState()
    val disappearingSeconds by viewModel.disappearingMessageSeconds.collectAsState()

    var showTimerDialog by remember { mutableStateOf(false) }
    var showDestroyConfirm by remember { mutableStateOf(false) }

    var pastedBundle by remember { mutableStateOf("") }
    var pastedCiphertext by remember { mutableStateOf("") }
    var pastedIsPreKeyMessage by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf("") }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val recorder = remember { VoiceNoteRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }

    fun startRecordingAndSend() {
        if (recorder.start()) {
            isRecording = true
        }
    }
    fun stopRecordingAndSend() {
        isRecording = false
        val bytes = recorder.stopAndRead() ?: return
        val fileName = "voice_note_${System.currentTimeMillis()}.m4a"
        viewModel.sendAttachment(bytes, fileName, "audio/mp4")
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecordingAndSend()
    }
    fun onVoiceNoteButtonPressed() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startRecordingAndSend() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val callAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) callManager?.startCall(contactRandomId, isVideo = false)
    }
    fun onCallButtonPressed() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) callManager?.startCall(contactRandomId, isVideo = false) else callAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val callVideoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) callManager?.startCall(contactRandomId, isVideo = true)
    }
    fun onVideoCallButtonPressed() {
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCamera) {
            callManager?.startCall(contactRandomId, isVideo = true)
        } else {
            callVideoPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.sendAttachment(it) }
    }
    // Bound freshly each time Save is tapped (see AttachmentMessageRow's onSave) since
    // CreateDocument's contract needs the suggested name at launch time, not composition time.
    var pendingSaveSourceFileName by remember { mutableStateOf<String?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destUri ->
        val sourceFileName = pendingSaveSourceFileName
        pendingSaveSourceFileName = null
        if (destUri != null && sourceFileName != null) {
            coroutineScope.launch {
                val bytes = viewModel.readDecryptedAttachmentFile(sourceFileName)
                if (bytes != null) {
                    context.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshSessionState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactLabel) },
                actions = {
                    if (callManager != null) {
                        TextButton(onClick = { onCallButtonPressed() }) { Text("Call") }
                        TextButton(onClick = { onVideoCallButtonPressed() }) { Text("Video") }
                    }
                    TextButton(onClick = { showTimerDialog = true }) { Text("Timer") }
                    TextButton(onClick = { showDestroyConfirm = true }) { Text("Destroy") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            if (viewModel.automaticModeAvailable) {
                Text(peerStatusLabel(peerStatus), modifier = Modifier.padding(bottom = 8.dp))
            }

            status?.let {
                Text(it, modifier = Modifier.padding(bottom = 8.dp))
                TextButton(onClick = viewModel::clearStatus) { Text("Dismiss") }
            }

            if (!hasSession && peerStatus != PeerStatus.CONNECTED) {
                Text(
                    if (viewModel.automaticModeAvailable)
                        "Waiting on automatic session setup. If this contact is offline, you can still exchange keys manually below."
                    else
                        "No signaling server configured (Settings) - exchange key bundles manually to start a session."
                )

                Button(onClick = viewModel::generateMyBundle, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Generate my bundle to share")
                }
                myBundle?.let {
                    Text("Your bundle (copy this to them):", modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }

                OutlinedTextField(
                    value = pastedBundle,
                    onValueChange = { pastedBundle = it },
                    label = { Text("Paste their bundle here") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Button(
                    onClick = { viewModel.establishSession(pastedBundle) },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Establish session") }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(messages) { message ->
                        val prefix = if (message.direction == MessageDirection.OUTGOING) "You: " else "${contactLabel}: "
                        if (message.attachmentId != null) {
                            Text(prefix, modifier = Modifier.padding(top = 4.dp))
                            AttachmentMessageRow(
                                attachmentId = message.attachmentId,
                                fallbackFileName = message.body,
                                viewModel = viewModel,
                                onSave = { sourceFileName, suggestedName ->
                                    pendingSaveSourceFileName = sourceFileName
                                    saveFileLauncher.launch(suggestedName)
                                }
                            )
                        } else {
                            Text("$prefix${message.body}", modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Message") },
                        modifier = Modifier.weight(1f)
                    )
                    if (viewModel.attachmentModeAvailable) {
                        TextButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                            Text("Attach")
                        }
                        TextButton(onClick = {
                            if (isRecording) stopRecordingAndSend() else onVoiceNoteButtonPressed()
                        }) {
                            Text(if (isRecording) "Stop & send" else "🎤")
                        }
                    }
                    Button(
                        onClick = { viewModel.sendMessage(draft); draft = "" },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text(if (peerStatus == PeerStatus.CONNECTED) "Send" else "Encrypt") }
                }

                if (peerStatus != PeerStatus.CONNECTED) {
                    lastOutgoingCiphertext?.let {
                        Text("No live connection - share this ciphertext with them manually:", modifier = Modifier.padding(top = 8.dp))
                        Text(it)
                    }

                    Text("Received a ciphertext from them? Paste it:", modifier = Modifier.padding(top = 16.dp))
                    OutlinedTextField(
                        value = pastedCiphertext,
                        onValueChange = { pastedCiphertext = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row {
                        Checkbox(checked = pastedIsPreKeyMessage, onCheckedChange = { pastedIsPreKeyMessage = it })
                        Text("This is their first message to me (uses my prekey)", modifier = Modifier.padding(top = 12.dp))
                    }
                    Button(onClick = {
                        viewModel.receiveMessage(pastedCiphertext, pastedIsPreKeyMessage)
                        pastedCiphertext = ""
                    }) { Text("Decrypt") }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
        }
    }

    if (showTimerDialog) {
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    Text("New messages sent after this point will be removed on both sides once the timer elapses, starting when each copy is delivered.")
                    DisappearingTimerOption.entries.forEach { option ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            RadioButton(
                                selected = disappearingSeconds == option.seconds,
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
                    "Permanently erases every message and attachment in this conversation " +
                        "on this device, and destroys the encrypted session with $contactLabel " +
                        "- messaging them again will need a brand-new key exchange. This cannot " +
                        "be undone, and it doesn't affect their copy of the conversation."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDestroyConfirm = false
                    viewModel.destroyConversation(onBack)
                }) { Text("Destroy") }
            },
            dismissButton = { TextButton(onClick = { showDestroyConfirm = false }) { Text("Cancel") } }
        )
    }
}

private fun peerStatusLabel(status: PeerStatus?): String = when (status) {
    PeerStatus.IDLE, null -> "Not connected"
    PeerStatus.REQUESTING_BUNDLE -> "Requesting key bundle…"
    PeerStatus.CONNECTING -> "Connecting…"
    PeerStatus.CONNECTED -> "Connected (live)"
    PeerStatus.OFFLINE -> "Contact appears offline"
    PeerStatus.FAILED -> "Connection failed"
}
