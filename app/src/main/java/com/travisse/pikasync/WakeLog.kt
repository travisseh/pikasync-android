package com.travisse.pikasync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * One row per wake event. Persisted locally AND posted to ntfy.sh so wakes are
 * visible on any device without running a server. Mirror of iOS WakeLog.swift.
 */
data class WakeEvent(
    val timestamp: Long,      // epoch millis
    val trigger: String,      // foreground | job_content | work_periodic | push
    val newPhotos: Int,
    val totalPhotos: Int,
    val note: String = "",
)

object WakeLog {
    const val NTFY_TOPIC = "pikasync-android-trav-8347" // subscribe: https://ntfy.sh/pikasync-android-trav-8347

    private fun file(context: Context) = File(context.filesDir, "wake-log.json")

    @Synchronized
    fun load(context: Context): List<WakeEvent> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WakeEvent(
                    timestamp = o.getLong("timestamp"),
                    trigger = o.getString("trigger"),
                    newPhotos = o.getInt("newPhotos"),
                    totalPhotos = o.getInt("totalPhotos"),
                    note = o.optString("note", ""),
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun record(context: Context, trigger: String, newPhotos: Int, totalPhotos: Int, note: String = "") {
        val event = WakeEvent(System.currentTimeMillis(), trigger, newPhotos, totalPhotos, note)
        val events = listOf(event) + load(context)
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("timestamp", e.timestamp)
                put("trigger", e.trigger)
                put("newPhotos", e.newPhotos)
                put("totalPhotos", e.totalPhotos)
                put("note", e.note)
            })
        }
        file(context).writeText(arr.toString())
        post(event)
    }

    /** Fire-and-forget wake beacon. ntfy.sh needs no account; the topic IS the auth. */
    private fun post(e: WakeEvent) {
        thread(isDaemon = true) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                fmt.timeZone = TimeZone.getDefault()
                val line = "[${e.trigger}] ${fmt.format(Date(e.timestamp))} new=${e.newPhotos} total=${e.totalPhotos}" +
                    if (e.note.isNotEmpty()) " ${e.note}" else ""
                val conn = URL("https://ntfy.sh/$NTFY_TOPIC").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.outputStream.use { it.write(line.toByteArray()) }
                conn.responseCode // force the request
                conn.disconnect()
            } catch (_: Exception) {
                // beacon is best-effort; never crash a background wake over it
            }
        }
    }
}
