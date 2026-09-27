package com.notrace.messenger.identity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notrace.messenger.identity.domain.IdentityRepository
import com.notrace.messenger.identity.domain.RandomIdGenerator

/**
 * Shown once, right after onboarding, before the contacts/conversations
 * screen. Generates the random ID (first call to getOrCreateSelfIdentity
 * anywhere in the app), lets the user optionally pick a username, and
 * makes the ID easy to copy/share since that's the only way a contact
 * can add this device back (V1 has no directory/lookup).
 */
@Composable
fun IdentitySetupScreen(
    repository: IdentityRepository,
    onContinue: () -> Unit,
    viewModel: IdentityViewModel = viewModel(factory = IdentityViewModel.Factory(repository))
) {
    val selfIdentity by viewModel.selfIdentity.collectAsState()
    val usernameError by viewModel.usernameError.collectAsState()
    var usernameInput by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.ensureIdentityLoaded() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Your NoTrace ID", fontWeight = FontWeight.SemiBold)
            Text(
                text = selfIdentity?.randomId?.let { RandomIdGenerator.format(it) } ?: "Generating…",
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                "Share this ID with people you want to message. There's no directory " +
                    "and no phone number involved — this is the only way someone can add you.",
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = usernameInput,
                onValueChange = { usernameInput = it },
                label = { Text("Username (optional, changeable later)") },
                modifier = Modifier.fillMaxWidth(),
                isError = usernameError != null,
                supportingText = { usernameError?.let { Text(it) } }
            )

            Button(
                onClick = {
                    if (usernameInput.isBlank()) {
                        onContinue()
                    } else {
                        viewModel.setUsername(usernameInput, onSuccess = onContinue)
                    }
                },
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text("Continue")
            }
        }
    }
}
