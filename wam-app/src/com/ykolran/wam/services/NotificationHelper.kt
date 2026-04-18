package com.ykolran.wam.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ykolran.wam.R
import com.ykolran.wam.ui.ConversationsActivity

object NotificationHelper {

    private const val CHANNEL_ID   = "wa_summaries"
    private const val CHANNEL_NAME = "Conversation Summaries"
    private const val NOTIF_GROUP  = "com.ykolran.wam.SUMMARIES"

    /** Call once at app startup (safe to call multiple times). */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Latest WhatsApp conversation summaries"
            enableVibration(false)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Post (or update) a notification for a conversation summary.
     * Each conversation gets its own notification slot (keyed by conversationId hash).
     */
    fun postSummaryNotification(
        context: Context,
        contactName: String,
        summary: String,
        sentiment: String?,
        conversationId: String
    ) {
        val intent = Intent(context, ConversationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("highlight_conversation_id", conversationId)
        }
        val pending = PendingIntent.getActivity(
            context, conversationId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sentimentEmoji = when (sentiment?.lowercase()) {
            "positive"  -> "😊 "
            "negative"  -> "😟 "
            "urgent"    -> "🚨 "
            "concerned" -> "😕 "
            "friendly"  -> "🤝 "
            else        -> ""
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$sentimentEmoji$contactName")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(NOTIF_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(conversationId.hashCode(), notification)
    }

    fun cancelNotification(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(conversationId.hashCode())
    }
}
