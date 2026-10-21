package app.polyphony.inbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.polyphony.MainActivity
import app.polyphony.R
import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.core.domain.model.Server
import app.polyphony.navigation.DeepLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System notifications on the "Music requests" channel for new continuo inbox items (#118).
 * Each is tagged by request ([InboxLogic.tag]), so a second notification about the same request
 * replaces the first instead of stacking. Tapping one opens its deep link with a
 * [DeepLink.Receipt], which marks it read.
 */
@Singleton
class RequestNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    /** POST_NOTIFICATIONS (Android 13+) granted and notifications on for the app. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // canPost() checks POST_NOTIFICATIONS (API 33+); lint cannot see through it.
    @SuppressLint("MissingPermission")
    fun post(server: Server, item: InboxNotification) {
        if (!canPost()) return
        ensureChannel()
        val link = DeepLink.withReceipt(InboxLogic.link(item), server.id, item.id)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            // Distinct per notification, so each keeps its own link.
            (item.id xor (item.id ushr 32)).toInt() xor server.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = item.title.ifBlank { context.getString(R.string.inbox_channel_name) }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(app.polyphony.core.playback.R.drawable.ic_stat_polyphony)
            .setContentTitle(title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        item.body?.let { body ->
            builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        item.createdAtMillis?.let { builder.setWhen(it).setShowWhen(true) }
        runCatching { NotificationManagerCompat.from(context).notify(InboxLogic.tag(server.id, item), NOTIFICATION_ID, builder.build()) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.inbox_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.inbox_channel_description)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "music_requests"
        /** Shared by every request notification; the tag tells them apart. */
        const val NOTIFICATION_ID = 0x5052 // "PR"
    }
}
