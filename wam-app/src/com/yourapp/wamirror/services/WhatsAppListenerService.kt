package com.ykolran.wam.services

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.Message
import kotlinx.coroutines.*

/**
 * Listens to WhatsApp notifications and forwards message content to the local server.
 *
 * SETUP REQUIRED:
 *   1. Add to AndroidManifest.xml:
 *      <service android:name=".services.WhatsAppListenerService"
 *               android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
 *               android:exported="true">
 *          <intent-filter>
 *              <action android:name="android.service.notification.NotificationListenerService"/>
 *          </intent-filter>
 *      </service>
 *   2. User must grant Notification Access in Android Settings > Notifications > Notification Access
 */
class WhatsAppListenerService : NotificationListenerService() {

    private val TAG = "WAListener"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"  // WhatsApp Business
        )
        // Derive a stable conversation ID from the notification title (contact/group name)
        fun makeConvId(pkg: String, title: String): String =
            "${pkg}_${title.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Skip empty, "Tap to load" or summary notifications
        if (text.isBlank() || text.startsWith("Tap to") || title == "WhatsApp") return

        // Handle bundled messages (several at once)
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val isGroup = sbn.notification.extras.getBoolean("android.isGroupConversation", false)

        val conversationId = makeConvId(sbn.packageName, title)

        if (messages != null && messages.isNotEmpty()) {
            // Multiple queued messages - send each one
            messages.forEach { bundle ->
                val msgBundle = bundle as? android.os.Bundle ?: return@forEach
                val sender = msgBundle.getCharSequence("sender")?.toString() ?: title
                val msgText = msgBundle.getCharSequence("text")?.toString() ?: return@forEach
                val timestamp = msgBundle.getLong("time", System.currentTimeMillis()) / 1000
                sendMessage(conversationId, title, sender, msgText, timestamp, isGroup)
            }
        } else {
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
                val message = Message(
                    conversationId = convId,
                    contactName = contactName,
                    sender = sender,
                    text = text,
                    timestamp = timestamp,
                    isGroup = isGroup
                )
                val response = ApiClient.api.postMessage(message)
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
