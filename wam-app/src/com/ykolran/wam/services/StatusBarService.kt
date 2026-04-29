package com.ykolran.wam.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.SummaryUpdate
import com.ykolran.wam.ui.ConversationsActivity
import kotlinx.coroutines.*

/**
 * Persistent foreground service that:
 *  - Shows a green (connected) or red (disconnected) icon in the status bar at all times
 *  - Tracks unread summaries and shows a count badge in the persistent notification
 *  - Posts individual expandable child notifications for each new summary
 *  - Periodically pings the server to detect connection loss
 */
class StatusBarService : Service() {

    companion object {
        const val CHANNEL_STATUS   = "wam_status"
        const val CHANNEL_SUMMARIES = "wam_summaries"

        // The single persistent notification ID (always visible in status bar)
        private const val NOTIF_ID_STATUS = 1

        // Summary notifications start at ID 1000 to avoid collision
        private const val NOTIF_ID_SUMMARY_BASE = 1000

        private const val GROUP_SUMMARIES = "com.ykolran.wam.SUMMARIES"

        // Ping interval
        private const val PING_INTERVAL_MS = 30_000L

        // Actions sent to the service
        const val ACTION_MARK_ALL_READ = "com.ykolran.wam.MARK_ALL_READ"
        const val ACTION_START         = "com.ykolran.wam.START_STATUS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StatusBarService::class.java).apply {
                action = ACTION_START
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pingJob: Job? = null

    // Track unread summaries: conversationId → SummaryUpdate
    private val unreadSummaries = LinkedHashMap<String, SummaryUpdate>()
    private var isConnected = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID_STATUS, buildStatusNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MARK_ALL_READ -> {
                unreadSummaries.clear()
                cancelAllSummaryNotifications()
                updateStatusNotification()
            }
            else -> {
                startPinging()
                observeWebSocket()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Channel setup ─────────────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Status channel — low importance so it's silent but always visible
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_STATUS, "Connection Status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows server connection state"
            setShowBadge(false)
        })

        // Summary channel — default importance so summaries make sound/vibrate
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SUMMARIES, "Conversation Summaries", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "New WhatsApp conversation summaries"
            setShowBadge(true)
        })
    }

    // ── Status notification (persistent, always in status bar) ────────────

    private fun buildStatusNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConversationsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StatusBarService::class.java).apply { action = ACTION_MARK_ALL_READ },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = unreadSummaries.size

        val (iconRes, colorRes, titleText) = when {
            !isConnected -> Triple(
                R.drawable.ic_status_disconnected,
                R.color.statusDisconnected,
                getString(R.string.status_no_connection)
            )
            count > 0 -> Triple(
                R.drawable.ic_status_connected,
                R.color.statusConnected,
                getString(R.string.status_unread_summaries, count)
            )
            else -> Triple(
                R.drawable.ic_status_connected,
                R.color.statusConnected,
                getString(R.string.status_connected)
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(iconRes)
            .setColor(ContextCompat.getColor(this, colorRes))
            .setContentTitle(titleText)
            .setOngoing(true)           // cannot be dismissed by the user
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Show each unread summary as a line in the expanded view
        if (count > 0) {
            val style = NotificationCompat.InboxStyle()
                .setSummaryText(getString(R.string.status_unread_summaries, count))
            unreadSummaries.values.take(5).forEach { update ->
                val emoji = sentimentEmoji(update.sentiment)
                style.addLine("$emoji ${update.contactName}: ${update.summary.take(60)}")
            }
            builder.setStyle(style)
            builder.addAction(
                R.drawable.ic_archive,
                getString(R.string.action_mark_all_read),
                markReadIntent
            )
        }

        return builder.build()
    }

    private fun updateStatusNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_STATUS, buildStatusNotification())
    }

    // ── Individual summary child notifications ────────────────────────────

    private fun postSummaryNotification(update: SummaryUpdate) {
        val tapIntent = PendingIntent.getActivity(
            this,
            update.conversationId.hashCode(),
            Intent(this, ConversationsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("highlight_conversation_id", update.conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emoji = sentimentEmoji(update.sentiment)
        val notif = NotificationCompat.Builder(this, CHANNEL_SUMMARIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .setContentTitle("$emoji ${update.contactName}")
            .setContentText(update.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.summary))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_SUMMARIES)
            .build()

        val notifId = NOTIF_ID_SUMMARY_BASE + (update.conversationId.hashCode() and 0x7FFFFFFF) % 900
        getSystemService(NotificationManager::class.java).notify(notifId, notif)
    }

    private fun cancelAllSummaryNotifications() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.id >= NOTIF_ID_SUMMARY_BASE }
            .forEach { nm.cancel(it.id) }
    }

    // ── Connectivity ping ─────────────────────────────────────────────────

    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                checkConnection()
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkConnection() {
        val wasConnected = isConnected
        isConnected = try {
            val response = withContext(Dispatchers.IO) { ApiClient.api.healthCheck() }
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
        if (wasConnected != isConnected) {
            updateStatusNotification()
            Log.i("StatusBarService", "Connection state → $isConnected")
        }
    }

    // ── WebSocket summary observer ────────────────────────────────────────

    private fun observeWebSocket() {
        scope.launch {
            ApiClient.summaryUpdates.collect { update ->
                when (update.type) {
                    "summary_updated" -> {
                        isConnected = true
                        unreadSummaries[update.conversationId] = update
                        postSummaryNotification(update)
                        updateStatusNotification()
                    }
                    "conversation_archived" -> {
                        unreadSummaries.remove(update.conversationId)
                        updateStatusNotification()
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sentimentEmoji(sentiment: String?) = when (sentiment?.lowercase()) {
        "positive"  -> "😊"
        "negative"  -> "😟"
        "urgent"    -> "🚨"
        "concerned" -> "😕"
        "friendly"  -> "🤝"
        else        -> "💬"
    }
}
