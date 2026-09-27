package com.notrace.messenger.attachment.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.notrace.messenger.attachment.data.AttachmentKind
import com.notrace.messenger.attachment.data.AttachmentStatus
import com.notrace.messenger.crypto.ui.ChatViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Renders one attachment (Phase 7): a thumbnail for a completed image,
 * or a filename + progress/status line for anything else / still in
 * flight. onSave lets the user explicitly export the decrypted file to
 * their own storage (plan Section 8 "shared-storage boundary" - never
 * auto-saved, only on an explicit tap).
 */
@Composable
fun AttachmentMessageRow(
    attachmentId: String,
    fallbackFileName: String,
    viewModel: ChatViewModel,
    onSave: (fileName: String, suggestedName: String) -> Unit
) {
    val attachment by observeAsState(viewModel.observeAttachment(attachmentId))
    val entity = attachment

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        val name = entity?.fileName ?: fallbackFileName
        val statusLabel = when (entity?.status) {
            AttachmentStatus.SENDING -> "Sending ${entity.completedChunks}/${entity.totalChunks}…"
            AttachmentStatus.RECEIVING -> "Receiving ${entity.completedChunks}/${entity.totalChunks}…"
            AttachmentStatus.FAILED -> "Failed (integrity check or send error)"
            AttachmentStatus.COMPLETE -> "Ready"
            AttachmentStatus.PENDING, null -> "…"
        }

        if (entity?.kind == AttachmentKind.IMAGE && entity.status == AttachmentStatus.COMPLETE && entity.thumbnailFileName != null) {
            var thumbnailBytes by remember(entity.thumbnailFileName) { mutableStateOf<ByteArray?>(null) }
            LaunchedEffect(entity.thumbnailFileName) {
                thumbnailBytes = viewModel.readDecryptedAttachmentFile(entity.thumbnailFileName!!)
            }
            thumbnailBytes?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = name,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }
        }

        Text("📎 $name")
        Text(statusLabel)

        if (entity?.status == AttachmentStatus.COMPLETE && entity.localFileName != null) {
            TextButton(onClick = { onSave(entity.localFileName, entity.fileName) }) {
                Text("Save to device")
            }
        }
    }
}

@Composable
private fun <T> observeAsState(flow: Flow<T>): androidx.compose.runtime.State<T?> {
    val state = remember { mutableStateOf<T?>(null) }
    LaunchedEffect(flow) { flow.collect { state.value = it } }
    return state
}
