package com.ykolran.wam.services

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.os.BundleCompat
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.OutboundMessage
import kotlinx.coroutines.*

/**
 * Listens to WhatsApp notifications and forwards message content to the local server.
 */
class WhatsAppListenerService : NotificationListenerService() {

    private val TAG = "WAListener"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"  // WhatsApp Business
        )

        /**
         * Derive a stable conversation ID from the notification title (contact/group name).
         *
         * Strategy:
         * 1. Try to use the OS-provided conversationId extra (most stable — survives renames).
         * 2. Strip the title to only alphanumeric + digits, collapse runs of underscores.
         *    Phone numbers like "+972-50-123-4567" become "972501234567" (still unique & readable).
         */
        fun makeConvId(pkg: String, title: String, osConvId: String? = null): String {
            // Prefer the OS conversation ID if WhatsApp provides it
            if (!osConvId.isNullOrBlank()) {
                return "${pkg}_${osConvId}"
            }
            // Strip to alphanumeric only (keeps digits from phone numbers, drops separators)
            val normalized = title
                .replace(Regex("[^a-zA-Z0-9]"), "_")  // non-alphanumeric → underscore
                .replace(Regex("_+"), "_")             // collapse consecutive underscores
                .trim('_')                             // strip leading/trailing underscores
                .lowercase()
            return "${pkg}_${normalized.ifEmpty { "unknown" }}"
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Skip empty, "Tap to load" or summary notifications
        if (text.isBlank() || text.startsWith("Tap to") || title == "WhatsApp") return

        // Handle bundled messages (several at once)
        val messages = BundleCompat.getParcelableArray(extras, Notification.EXTRA_MESSAGES, Parcelable::class.java)
        val isGroup = extras.getBoolean("android.isGroupConversation", false)

        // Try to get WhatsApp's own stable conversation ID from the notification extras
        val osConvId = extras.getString("android.conversationId")
            ?: sbn.notification.shortcutId  // fallback: shortcut ID is also stable per chat

        val conversationId = makeConvId(sbn.packageName, title, osConvId)

        if (!messages.isNullOrEmpty()) {
            for (parcelable in messages) {
                val msgBundle = parcelable as? Bundle ?: continue
                val sender = msgBundle.getCharSequence("sender")?.toString() ?: title
                val msgText = msgBundle.getCharSequence("text")?.toString() ?: continue
                val timestamp = msgBundle.getLong("time", System.currentTimeMillis()) / 1000

                // ✅ Use sender as the conversation key for bundles — NOT the outer `title`
                // For groups, sender = individual person; for DMs, sender = contact name
                // So for groups, we still need the outer title (which should be group name)
                // Only fall back to title if it looks like a real name (not a number)
                val convTitle = if (title.all { it.isDigit() || it in listOf('+', '-', ' ', '(', ')') }) {
                    sender  // outer title is a number — use sender name instead
                } else {
                    title   // outer title is a real contact/group name — use it
                }

                val convId = makeConvId(sbn.packageName, convTitle, osConvId)
                sendMessage(convId, convTitle, sender, msgText, timestamp, isGroup)
            }
        }
        else {
            // Single message — infer sender from "Name: message" pattern for groups
            val (sender, msgText) = if (isGroup && text.contains(": ")) {
                val idx = text.indexOf(": ")
                text.substring(0, idx) to text.substring(idx + 2)
            } else {
                title to text
            }
            sendMessage(conversationId, title, sender, msgText, System.currentTimeMillis() / 1000, isGroup)
        }
    }

    private fun sendMessage(
        convId: String, contactName: String, sender: String,
        text: String, timestamp: Long, isGroup: Boolean
    ) {
        scope.launch {
            try {
                val body = OutboundMessage(
                    conversationId = convId,
                    contactName = contactName,
                    sender = sender,
                    text = text,
                    timestamp = timestamp,
                    isGroup = isGroup
                )
                val response = ApiClient.api.postMessage(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Server returned ${response.code()} for message from $contactName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to server: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
