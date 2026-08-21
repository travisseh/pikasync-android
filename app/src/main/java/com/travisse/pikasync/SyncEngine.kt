package com.travisse.pikasync

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The core question this POC answers: can Android wake this app to detect new
 * photos WITHOUT the user opening it? Mirror of iOS SyncEngine.swift.
 *
 * Three instrumented wake arms:
 *  1. JobScheduler + addTriggerContentUri on MediaStore.Images  -> trigger "job_content"
 *  2. WorkManager 15-minute periodic work                        -> trigger "work_periodic"
 *  3. FCM high-priority data message (stub, no Firebase project) -> trigger "push"
 */
object SyncEngine {
    const val CONTENT_JOB_ID = 1001
    private const val PREFS = "pikasync"
    private const val MARKER_KEY = "lastSyncDateAddedSec" // MediaStore DATE_ADDED is epoch seconds

    enum class PhotoAccess { FULL, PARTIAL, DENIED }

    fun photoAccess(context: Context): PhotoAccess {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) {
            when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
                Build.VERSION.SDK_INT >= 34 &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
                else -> PhotoAccess.DENIED
            }
        } else {
            if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) PhotoAccess.FULL else PhotoAccess.DENIED
        }
    }

    /** The "sync": query photos newer than the stored marker; no upload needed for the POC. */
    fun runSync(context: Context, trigger: String) {
        if (photoAccess(context) == PhotoAccess.DENIED) {
            WakeLog.record(context, trigger, -1, -1, "no permission")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marker = prefs.getLong(MARKER_KEY, 0L)
        val nowSec = System.currentTimeMillis() / 1000

        var total = 0
        var fresh = 0
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                null, null, null,
            )?.use { cursor ->
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    total++
                    if (cursor.getLong(dateCol) > marker) fresh++
                }
            }
        } catch (e: Exception) {
            WakeLog.record(context, trigger, -1, -1, "query failed: ${e.message}")
            return
        }

        prefs.edit().putLong(MARKER_KEY, nowSec).apply()
        WakeLog.record(context, trigger, fresh, total)
        Deadman.arm(context)
        scheduleContentJob(context) // content-trigger jobs are one-shot; always re-arm
    }

    // MARK: wake arm 1 — JobScheduler content trigger (fires when MediaStore.Images changes)

    fun scheduleContentJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(CONTENT_JOB_ID, ComponentName(context, PhotoContentJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(1_000)   // batch rapid-fire changes
            .setTriggerContentMaxDelay(10_000)     // but never wait more than 10s
            .build()
        scheduler.schedule(job)
    }

    // MARK: wake arm 2 — WorkManager 15-minute periodic work

    fun schedulePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "pikasync-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

/**
 * Dead-man's switch: a local notification 4 days out, re-armed (cancel + reschedule)
 * on every successful wake. It only ever fires if wakes STOP.
 */
object Deadman {
    private const val REQUEST_CODE = 2001
    const val FOUR_DAYS_MS = 4L * 24 * 3600 * 1000

    fun arm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, DeadmanReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.cancel(pending)
        val triggerAt = System.currentTimeMillis() + FOUR_DAYS_MS
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // exact-alarm permission not granted (Android 14 default): inexact fallback
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }
}
