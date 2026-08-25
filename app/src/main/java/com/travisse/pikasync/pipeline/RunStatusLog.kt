package com.travisse.pikasync.pipeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persists the outcome of every pipeline run (manual or background) to
 * files/last-run.json so failures can be pulled off the device with adb
 * instead of screenshotted. Mirror of the iOS RunStatusLog.
 */
object RunStatusLog {
    private const val MAX_ENTRIES = 20

    private fun file(context: Context) = File(context.filesDir, "last-run.json")

    private fun iso8601(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    fun write(
        context: Context,
        month: String,
        status: String,
        error: String?,
        stages: List<String>,
        trigger: String,
    ) {
        try {
            val history = load(context)
            val entry = JSONObject().apply {
                put("finishedAt", iso8601())
                put("month", month)
                put("status", status)
                put("error", error ?: JSONObject.NULL)
                put("stages", JSONArray(stages))
                put("trigger", trigger)
            }
            val out = JSONArray().put(entry)
            for (i in 0 until minOf(history.length(), MAX_ENTRIES - 1)) {
                out.put(history.getJSONObject(i))
            }
            file(context).writeText(out.toString(2))
        } catch (_: Exception) {
            // status logging is best-effort; never fail a run over it
        }
    }

    fun load(context: Context): JSONArray = try {
        JSONArray(file(context).readText())
    } catch (_: Exception) {
        JSONArray()
    }
}
