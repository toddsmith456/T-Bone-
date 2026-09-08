package social.tbone.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import social.tbone.MainActivity
import social.tbone.R

const val CHANNEL_MENTIONS = "mentions"
const val CHANNEL_BOOSTS = "boosts"
const val CHANNEL_REACTIONS = "reactions"
const val EXTRA_EVENT_ID = "eventId"

fun createNotificationChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannels(
        listOf(
            NotificationChannel(CHANNEL_MENTIONS, "Mentions & Replies", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Replies and direct mentions" },
            NotificationChannel(CHANNEL_BOOSTS, "Boosts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When someone boosts your note" },
            NotificationChannel(CHANNEL_REACTIONS, "Reactions", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Likes and emoji reactions" },
        )
    )
}

fun showNotification(
    context: Context,
    channel: String,
    title: String,
    text: String,
    eventId: String,
) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_EVENT_ID, eventId)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        eventId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()
    context.getSystemService(NotificationManager::class.java)
        .notify(eventId.hashCode(), notification)
}
