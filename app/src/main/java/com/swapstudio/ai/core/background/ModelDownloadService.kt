package com.swapstudio.ai.core.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.swapstudio.ai.R
import com.swapstudio.ai.core.models.DownloadStatus
import com.swapstudio.ai.core.models.ModelManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps model downloads running when the app is not in the foreground.
 *
 * The actual transfer lives in [ModelManager] (it survives configuration changes and screen exits); this
 * service only mirrors the combined progress into a notification and holds a `dataSync` foreground slot
 * so Android does not kill the process. Starting it is idempotent.
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject
    lateinit var models: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        SwapForegroundService.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALL -> {
                startForegroundCompat()
                models.downloadAll()
                watch()
            }
            ACTION_START_ONE -> {
                val id = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                startForegroundCompat()
                models.resumeDownload(id)
                watch()
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Polls the model states and updates the notification until nothing is in flight. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                val descriptors = models.models.value
                val states = descriptors.map { models.downloader.stateOf(it.id).value }
                val active = states.filter {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.VERIFYING
                }
                if (active.isEmpty()) {
                    val installed = descriptors.count { models.store.isInstalled(it) }
                    notify(
                        title = getString(R.string.notif_models_ready_title),
                        text = getString(R.string.notif_models_ready_text, installed, descriptors.size),
                        progress = -1,
                    )
                    delay(2500)
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                val totalProgress = active.map { it.progress.coerceAtLeast(0f) }.average().toFloat()
                val speed = active.sumOf { it.speedBytesPerSecond } / (1024.0 * 1024.0)
                notify(
                    title = getString(R.string.notif_download_title),
                    text = getString(R.string.notif_download_text, active.size, totalProgress * 100, speed),
                    progress = (totalProgress * 100).toInt(),
                )
                delay(700)
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = build("Downloading models", "Preparing", -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notify(title: String, text: String, progress: Int) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, build(title, text, progress))
        } catch (_: SecurityException) {
        }
    }

    private fun build(title: String, text: String, progress: Int) = NotificationCompat.Builder(this, SwapForegroundService.CHANNEL_DOWNLOADS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply {
            if (progress < 0) setProgress(0, 0, true) else setProgress(100, progress.coerceIn(0, 100), false)
        }
        .build()

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_ALL = "com.swapstudio.ai.action.DOWNLOAD_ALL"
        const val ACTION_START_ONE = "com.swapstudio.ai.action.DOWNLOAD_ONE"
        const val ACTION_STOP = "com.swapstudio.ai.action.DOWNLOAD_STOP"
        const val EXTRA_MODEL_ID = "model_id"
        private const val NOTIFICATION_ID = 1100

        /** Starts downloading every missing required model in the background. */
        fun startAll(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply { action = ACTION_START_ALL }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Starts (or resumes) one model in the background. */
        fun startOne(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_ONE
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Stops the watcher; transfers keep their state and can be resumed later. */
        fun stop(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).apply { action = ACTION_STOP })
        }
    }
}
