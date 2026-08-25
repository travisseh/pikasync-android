package com.travisse.pikasync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.travisse.pikasync.pipeline.PipelineRunner
import java.util.Calendar

/**
 * Background book generation, mirror of iOS AutoBook: on a background wake,
 * generate LAST month's book — once per calendar month — and notify. The
 * success marker is set ONLY when the run succeeds, so failures retry on the
 * next wake. Unlike iOS there is no budget chunking: a WorkManager wake fits
 * the whole run.
 */
object AutoBook {
    private const val PREFS = "autobook"

    suspend fun generateIfDue(context: Context) {
        val now = Calendar.getInstance()
        val marker = "autoBook-%04d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(marker, false)) return

        val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val year = prev.get(Calendar.YEAR)
        val month = prev.get(Calendar.MONTH) + 1
        val label = "%04d-%02d".format(year, month)

        WakeLog.record(context, "bg_book", 0, 0, "generating book for $label")
        val t0 = System.currentTimeMillis()
        val result = PipelineRunner(context).run(year, month, trigger = "bg") { }
        val secs = (System.currentTimeMillis() - t0) / 1000

        val error = result.error
        if (error != null) {
            // Mirror iOS: a too-thin month is "done", not a retry loop.
            val tooSmall = error.contains("No photos found") || error.contains("pick a busier one")
            if (tooSmall) {
                prefs.edit().putBoolean(marker, true).apply()
                WakeLog.record(context, "bg_book", 0, 0, "skipped $label: ${error.take(80)}")
            } else {
                WakeLog.record(context, "bg_book", -1, -1, "failed after ${secs}s: ${error.take(120)}")
            }
            return
        }

        prefs.edit().putBoolean(marker, true).apply()
        val picks = result.judge?.selections?.size ?: 0
        WakeLog.record(context, "bg_book", picks, 0, "book ready in ${secs}s — ${result.judge?.title ?: ""}")
        notifyReady(context, result.judge?.title ?: "monthly", label)
    }

    private fun notifyReady(context: Context, title: String, label: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channelId = "autobook"
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Photobooks", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val notification = android.app.Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Your \"$title\" book is ready")
                .setContentText("Pikabook made your $label book in the background. Open the app to see it.")
                .setAutoCancel(true)
                .build()
            nm.notify(2, notification)
        } catch (_: Exception) {
            // notification is best-effort; the book itself is already recorded
        }
    }
}
