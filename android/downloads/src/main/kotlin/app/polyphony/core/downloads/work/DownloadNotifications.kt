package app.polyphony.core.downloads.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import app.polyphony.core.domain.repository.TransferRate
import app.polyphony.core.downloads.R
import app.polyphony.core.downloads.engine.TransferMeter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of music downloads"
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(
        done: Int,
        total: Int,
        progress: Float,
        indeterminate: Boolean,
        cancelIntent: PendingIntent?,
        rate: TransferRate? = null,
    ): Notification {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        val title = if (total > 0) "Downloading $done of $total" else "Downloading"
        val text = when {
            indeterminate -> "Starting…"
            rate != null -> "$pct% · ${TransferMeter.describe(rate)}"
            else -> "$pct%"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct, indeterminate)
        if (cancelIntent != null) {
            builder.addAction(0, "Cancel", cancelIntent)
        }
        return builder.build()
    }

    fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    // canPost() checks POST_NOTIFICATIONS (API 33+) and the channel state; lint cannot see through it.
    @SuppressLint("MissingPermission")
    fun update(notification: Notification) {
        if (!canPost()) return
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** Songs the storage cap removed, so they don't just vanish (they come back when there's room). */
    @SuppressLint("MissingPermission")
    fun evicted(count: Int, limitMb: Long) {
        if (count <= 0 || !canPost()) return
        val limit = if (limitMb >= 1024) String.format(Locale.US, "%.1f GB", limitMb / 1024.0) else "$limitMb MB"
        val songs = if (count == 1) "1 song" else "$count songs"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Removed $songs to stay under $limit")
            .setContentText("Only rule downloads go, never pinned ones; they come back when there's room.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Only rule downloads go, never pinned ones; they come back when there's room. " +
                    "Raise the limit in Downloads settings to keep more.",
            ))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(EVICTED_ID, notification) }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 0x5044 // "PD"
        const val EVICTED_ID = 0x5045
    }
}
