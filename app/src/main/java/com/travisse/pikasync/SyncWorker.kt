package com.travisse.pikasync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wake arm 2: WorkManager 15-minute periodic wake. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SyncEngine.runSync(applicationContext, "work_periodic")
        // Once per calendar month: generate last month's book in the background
        // (marker set only on success inside, so failures retry next wake).
        try {
            AutoBook.generateIfDue(applicationContext)
        } catch (e: Exception) {
            WakeLog.record(applicationContext, "bg_book", -1, -1, "crashed: ${e.toString().take(120)}")
        }
        Result.success()
    }
}
