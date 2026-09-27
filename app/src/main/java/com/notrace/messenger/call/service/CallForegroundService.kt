package com.notrace.messenger.call.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Minimal foreground service: exists only to keep an active call's
 * audio (and, as of Phase 9, video) alive if the user backgrounds the
 * app, and to satisfy Android's requirement that background
 * camera/microphone use show a persistent, user-visible notification
 * (the system's own privacy indicator does the rest - this app adds no
 * separate one, per Section 12 "privacy indicators" being about not
 * hiding what's already visible, not about building a duplicate).
 *
 * Started/stopped entirely by CallManager around the call's own
 * lifecycle (acceptCall/startCall's accept path -> start;
 * endLocally -> stop) - never runs at any other time (plan Section 13
 * "foreground services only when genuinely necessary").
 *
 * The manifest declares BOTH microphone and camera as possible
 * foregroundServiceTypes for this service, but a given call only
 * requests the type(s) it actually uses (ServiceCompat.startForeground's
 * type flags) - an audio-only call never triggers Android's camera
 * foreground-service permission check, only a video call does.
 */
class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contactLabel = intent?.getStringExtra(EXTRA_CONTACT_LABEL) ?: "contact"
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) ?: false
        ensureChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isVideo) "Ongoing video call" else "Ongoing call")
            .setContentText("Call with $contactLabel")
            .setSmallIcon(android.R.drawable.sym_call_incoming) // placeholder icon - a designed one belongs in Phase 22 UI polish
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        val typeFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, typeFlags)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val EXTRA_CONTACT_LABEL = "contact_label"
        const val EXTRA_IS_VIDEO = "is_video"
        private const val CHANNEL_ID = "notrace_calls"
        private const val NOTIFICATION_ID = 1001
    }
}
