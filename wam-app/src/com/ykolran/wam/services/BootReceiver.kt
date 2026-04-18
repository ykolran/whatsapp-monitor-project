package com.ykolran.wam.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts MediaWatcherService after device reboot or app update.
 * NotificationListenerService is managed by Android automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, MediaWatcherService::class.java))
        }
    }
}
