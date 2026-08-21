package com.travisse.pikasync

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Wake arm 1: JobScheduler fires this when MediaStore.Images changes (new photo).
 * Content-trigger jobs are one-shot, so we re-schedule inside onStartJob
 * (runSync also re-arms as a belt-and-suspenders).
 */
class PhotoContentJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        SyncEngine.scheduleContentJob(applicationContext) // re-arm FIRST, one-shot trigger
        thread {
            try {
                SyncEngine.runSync(applicationContext, "job_content")
            } finally {
                jobFinished(params, false)
            }
        }
        return true // work continues on our thread
    }

    override fun onStopJob(params: JobParameters): Boolean {
        WakeLog.record(applicationContext, "job_content", -1, -1, "stopped by system")
        return false
    }
}
