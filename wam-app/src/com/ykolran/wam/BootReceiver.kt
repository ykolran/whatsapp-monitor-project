package com.ykolran.wam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ykolran.wam.services.MediaWatcherService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MediaWatcherService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
