package com.travisse.pikasync.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.travisse.pikasync.pipeline.PipelineRunner
import com.travisse.pikasync.pipeline.RunStore
import com.travisse.pikasync.pipeline.ShareClient
import com.travisse.pikasync.pipeline.StageTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * In-progress book creation, observable from the Books list: a create request
 * becomes a loading entry at the top of the list with live stage text; tapping
 * it opens the dev-progress sheet. Survives navigation (not process death —
 * fine for the POC).
 */
object BookCreator {
    class Job(
        val year: Int,
        val month: Int,
        val monthLabel: String,
    ) {
        val stages: SnapshotStateList<StageTiming> = mutableStateListOf()
        var status by mutableStateOf("Getting started…")
        var error by mutableStateOf<String?>(null)
        val running get() = error == null && !done
        var done by mutableStateOf(false)
    }

    var active by mutableStateOf<Job?>(null)
        private set

    /** Bumped when the saved-runs list changed (new book, share info); UI re-reads RunStore. */
    var runsVersion by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context, year: Int, month: Int, monthLabel: String) {
        if (active?.running == true) return
        val job = Job(year, month, monthLabel)
        active = job
        val appContext = context.applicationContext
        scope.launch {
            val result = PipelineRunner(appContext).run(year, month, trigger = "manual") { t ->
                job.stages.add(t)
                job.status = t.name.substringAfter(' ')
            }
            if (result.error != null) {
                job.error = result.error
            } else {
                job.done = true
                active = null
            }
            runsVersion++
        }
    }

    fun dismissFailed() {
        if (active?.running != true) active = null
    }

    /** Best-effort retry of auto-shares that failed (called on app open). */
    fun retryNeedsShare(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            RunStore.load(appContext).filter { it.needsShare }.forEach { run ->
                try {
                    val share = ShareClient.upload(appContext, run)
                    RunStore.update(
                        appContext,
                        run.copy(shareId = share.shareId, shareUrl = share.url, needsShare = false)
                    )
                    runsVersion++
                } catch (_: Exception) {
                    // stays flagged; next open retries again
                }
            }
        }
    }

    fun notifyRunsChanged() {
        runsVersion++
    }
}
