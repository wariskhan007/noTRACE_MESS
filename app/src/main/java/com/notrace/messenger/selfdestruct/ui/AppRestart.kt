package com.notrace.messenger.selfdestruct.ui

import android.content.Context
import android.content.Intent

/**
 * Kills and relaunches the app process (Phase 11: destruction sequence
 * step 11, "remove key references from memory where practical").
 *
 * Why this is necessary and not just navigating back to onboarding:
 * AppContainer's lazily-created singletons (IdentityKeyPair objects,
 * the loaded NoTraceProtocolStore, etc.) stay resident in this
 * process's memory for as long as the process lives, regardless of
 * what screen is shown - simply popping the nav back-stack to
 * Onboarding does NOT clear any of that from the JVM heap. The only
 * way to genuinely guarantee no key material is left resident in
 * memory is for the process itself to end. Since a normal Android app
 * cannot force-zero arbitrary already-allocated memory before that
 * (the JVM/GC doesn't expose that), killing the process is the
 * practical, honest way to satisfy this step - matching the plan's own
 * "where practical" qualifier.
 *
 * Called after StorageWipeManager.wipeAll() has already completed (see
 * IdentityViewModel.deleteAccount) - by the time this runs, there is
 * nothing left on disk anyway; this only addresses the in-memory copy.
 */
fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
