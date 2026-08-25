package com.travisse.pikasync.pipeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persisted saved books, mirror of the iOS RunStore/SavedRun: JSON in the app
 * files dir so books survive restarts and background-generated books are
 * viewable. Photos are referenced by content URI (reloaded from MediaStore).
 */
data class SavedSelection(val uri: String, val page: Int)

data class SavedRun(
    val id: String,
    val createdAt: Long,
    val title: String,
    val monthLabel: String,   // "May 2026"
    val monthKey: String,     // "2026-05"
    val coverUri: String,
    val selections: List<SavedSelection>,
    val judgeInfo: String,
    val trigger: String,
)

object RunStore {
    private fun file(context: Context) = File(context.filesDir, "saved-runs.json")

    @Synchronized
    fun load(context: Context): List<SavedRun> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sels = o.getJSONArray("selections")
                SavedRun(
                    id = o.getString("id"),
                    createdAt = o.getLong("createdAt"),
                    title = o.getString("title"),
                    monthLabel = o.getString("monthLabel"),
                    monthKey = o.optString("monthKey"),
                    coverUri = o.getString("coverUri"),
                    selections = (0 until sels.length()).map { j ->
                        val s = sels.getJSONObject(j)
                        SavedSelection(s.getString("uri"), s.getInt("page"))
                    },
                    judgeInfo = o.optString("judgeInfo"),
                    trigger = o.optString("trigger", "manual"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, run: SavedRun) {
        save(context, listOf(run) + load(context))
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    private fun save(context: Context, runs: List<SavedRun>) {
        val arr = JSONArray()
        runs.forEach { r ->
            val sels = JSONArray()
            r.selections.forEach { s ->
                sels.put(JSONObject().put("uri", s.uri).put("page", s.page))
            }
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("createdAt", r.createdAt)
                    .put("title", r.title)
                    .put("monthLabel", r.monthLabel)
                    .put("monthKey", r.monthKey)
                    .put("coverUri", r.coverUri)
                    .put("selections", sels)
                    .put("judgeInfo", r.judgeInfo)
                    .put("trigger", r.trigger)
            )
        }
        val tmp = File(context.filesDir, "saved-runs.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(context))
    }

    /** Build a SavedRun from a successful pipeline result. Returns null if unsaveable. */
    fun fromResult(year: Int, month: Int, result: PipelineResult, trigger: String): SavedRun? {
        val judge = result.judge ?: return null
        val cover = result.shortlist.getOrNull(judge.coverIndex)?.uri?.toString() ?: return null
        val sels = judge.selections.mapNotNull { sel ->
            result.shortlist.getOrNull(sel.index)?.let { SavedSelection(it.uri.toString(), sel.page) }
        }.sortedBy { it.page }
        if (sels.isEmpty()) return null
        val label = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US).format(
            java.util.Calendar.getInstance().apply {
                set(year, month - 1, 1, 0, 0, 0)
            }.time
        )
        return SavedRun(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            title = judge.title,
            monthLabel = label,
            monthKey = String.format(java.util.Locale.US, "%04d-%02d", year, month),
            coverUri = cover,
            selections = sels,
            judgeInfo = "${judge.inputTokens} in / ${judge.outputTokens} out tokens",
            trigger = trigger,
        )
    }
}
