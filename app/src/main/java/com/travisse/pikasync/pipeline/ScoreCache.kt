package com.travisse.pikasync.pipeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent per-photo score records (mirror of iOS IncrementalScorer's store):
 * the expensive fields the pipeline computes per photo, keyed by MediaStore id,
 * so work done during onboarding (or an earlier run) is never repeated.
 * Flat JSON at files/score-cache.json, pruned to the newest ~1500 entries.
 */
object ScoreCache {
    data class Entry(
        val faceCount: Int,
        val faceQuality: Double,
        val sharpness: Double,
        val aHash: Long,
        val personIds: Set<String>,
        val isDoc: Boolean,
        val at: Long,
    )

    private const val MAX = 1500
    private var cache: MutableMap<Long, Entry>? = null

    private fun file(context: Context) = File(context.filesDir, "score-cache.json")

    @Synchronized
    fun load(context: Context): MutableMap<Long, Entry> {
        cache?.let { return it }
        val map = mutableMapOf<Long, Entry>()
        try {
            val root = JSONObject(file(context).readText())
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                val ids = mutableSetOf<String>()
                val arr = o.optJSONArray("p") ?: JSONArray()
                for (i in 0 until arr.length()) ids += arr.getString(i)
                map[key.toLong()] = Entry(
                    faceCount = o.optInt("fc"),
                    faceQuality = o.optDouble("fq", 0.0),
                    sharpness = o.optDouble("sh", 0.0),
                    aHash = o.optString("ah", "0").toLong(),
                    personIds = ids,
                    isDoc = o.optBoolean("doc", false),
                    at = o.optLong("at"),
                )
            }
        } catch (_: Exception) { /* missing/corrupt cache is fine */ }
        cache = map
        return map
    }

    @Synchronized
    fun put(context: Context, id: Long, e: Entry) {
        load(context)[id] = e
    }

    @Synchronized
    fun save(context: Context) {
        val map = load(context)
        // prune oldest entries beyond MAX so the file stays small
        if (map.size > MAX) {
            map.entries.sortedBy { it.value.at }.take(map.size - MAX).forEach { map.remove(it.key) }
        }
        val root = JSONObject()
        for ((id, e) in map) {
            root.put(
                id.toString(),
                JSONObject()
                    .put("fc", e.faceCount)
                    .put("fq", e.faceQuality)
                    .put("sh", e.sharpness)
                    .put("ah", e.aHash.toString())
                    .put("p", JSONArray(e.personIds.toList()))
                    .put("doc", e.isDoc)
                    .put("at", e.at),
            )
        }
        try {
            file(context).writeText(root.toString())
        } catch (_: Exception) { /* best-effort */ }
    }
}
