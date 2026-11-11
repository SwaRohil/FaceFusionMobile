package com.swapstudio.ai.core.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.swapstudio.ai.MainActivity
import com.swapstudio.ai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps a long render (or a model download) alive while the user switches apps
 * or turns the screen off.
 *
 * It is a *host* for the ongoing work, not the worker itself: the pipeline runs in a coroutine owned by
 * the ViewModel / WorkManager and only reports progress here. That keeps the service tiny and avoids
 * passing big objects through an Intent.
 *
 * Types: `dataSync|mediaProcessing` (declared in the manifest) so Android 14+ accepts the service for
 * both download and render workloads.
 */
class SwapForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.notif_processing_title)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notif_processing_text)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                startForegroundCompat(buildNotification(title, text, progress, indeterminate = progress < 0))
                _state.value = ServiceState(title, text, progress)
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: _state.value.title
                val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notif_processing_text)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                notify(buildNotification(title, text, progress, indeterminate = progress < 0))
                _state.value = ServiceState(title, text, progress)
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notify(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS was revoked while running: the service keeps working.
            }
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    /** Snapshot published to the UI (optional; the ViewModel owns the real progress). */
    data class ServiceState(val title: String, val text: String, val progress: Int)

    companion object {
        const val ACTION_START = "com.swapstudio.ai.action.START_FOREGROUND"
        const val ACTION_UPDATE = "com.swapstudio.ai.action.UPDATE_FOREGROUND"
        const val ACTION_STOP = "com.swapstudio.ai.action.STOP_FOREGROUND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"

        const val CHANNEL_PROCESSING = "processing"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_SAVED = "saved"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: SwapForegroundService? = null
        private val _state = MutableStateFlow(ServiceState("", "", -1))

        /** Last progress pushed into the service, observable from the UI. */
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        /** Registers the three notification channels (idempotent, safe to call from the Application). */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROCESSING, context.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Face swap rendering progress"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Model downloads with pause / resume"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Saved swaps, tap to open"
                },
            )
        }

        /** Starts (or updates) the foreground service. */
        fun start(context: Context, title: String, text: String, progress: Int = -1) {
            val intent = Intent(context, SwapForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Updates the notification while the service is running. */
        fun update(context: Context, title: String, text: String, progress: Int) {
            if (instance == null) return
            context.startService(
                Intent(context, SwapForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_PROGRESS, progress)
                },
            )
        }

        /** Stops the service. */
        fun stop(context: Context) {
            context.startService(Intent(context, SwapForegroundService::class.java).apply { action = ACTION_STOP })
        }

        /** Posts a one-shot "Saved" notification with an OPEN action. */
        fun notifySaved(context: Context, text: String, openIntent: PendingIntent) {
            val notification = NotificationCompat.Builder(context, CHANNEL_SAVED)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.notif_saved_title, text))
                .setContentText(context.getString(R.string.notif_saved_text))
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                try {
                    manager.notify(SAVED_NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {
                }
            }
        }

        const val SAVED_NOTIFICATION_ID = 1002
    }
}
