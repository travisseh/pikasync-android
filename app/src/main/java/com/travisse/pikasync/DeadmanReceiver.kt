package com.travisse.pikasync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires only if no wake has re-armed the alarm for 4 days. */
class DeadmanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channelId = "deadman"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Background sync alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PikaSync stopped syncing")
            .setContentText("No photo checks in 4 days. Open the app to resume the experiment.")
            .setAutoCancel(true)
            .build()
        nm.notify(1, notification)
        WakeLog.record(context, "deadman", -1, -1, "dead-man notification fired")
    }
}
