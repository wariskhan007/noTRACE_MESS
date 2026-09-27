package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notrace.messenger.R

/**
 * Phase 1: static onboarding shell only. Real identity creation
 * (random ID + username generation) is wired in Phase 3, replacing the
 * placeholder Continue button's destination logic here.
 *
 * The disclosure text below states the no-recovery policy (locked
 * decision #14) up front, per plan rule "privacy claims must be
 * precise and testable" and "explain infrastructure/retention/
 * self-destruct limitations" (Section 22).
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = stringResource(R.string.onboarding_title))
            Text(
                text = stringResource(R.string.onboarding_body),
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
            )
            Button(onClick = onContinue) {
                Text("Continue")
            }
        }
    }
}
