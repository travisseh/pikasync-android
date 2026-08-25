package com.travisse.pikasync.pipeline

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 7: POST contact sheets to the pikasync judge server, which holds the
 * Anthropic key and owns the judge prompt (captionless since round 3). The
 * device uploads sheets only — no API key on the device.
 */
object Judge {
    private const val ENDPOINT = "https://pikasync-judge.vercel.app/api/judge"

    /** Pool-scaled sizing: ~5/12 of the pool, clamped to 8..20 (further capped by sessions/scenes in the runner). */
    fun bookCount(shortlistSize: Int): Int = minOf(20, maxOf(8, shortlistSize * 5 / 12))

    /**
     * Vercel rejects request bodies over 4.5MB; step JPEG quality down until the
     * combined base64 payload fits with headroom.
     */
    private fun encodeSheets(sheets: List<Bitmap>): List<String> {
        for (quality in intArrayOf(70, 50, 35)) {
            val encoded = sheets.map { sheet ->
                val jpeg = ByteArrayOutputStream().also {
                    sheet.compress(Bitmap.CompressFormat.JPEG, quality, it)
                }.toByteArray()
                Base64.encodeToString(jpeg, Base64.NO_WRAP)
            }
            if (encoded.sumOf { it.length } < 3_800_000) return encoded
        }
        return sheets.map { sheet ->
            val jpeg = ByteArrayOutputStream().also {
                sheet.compress(Bitmap.CompressFormat.JPEG, 25, it)
            }.toByteArray()
            Base64.encodeToString(jpeg, Base64.NO_WRAP)
        }
    }

    suspend fun judge(
        sheets: List<Bitmap>,
        shortlist: List<PhotoItem>,
        bookCount: Int,
        monthLabel: String,
        correction: String? = null,
    ): JudgeResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("monthLabel", monthLabel)
                put("count", bookCount)
                put("maxIndex", shortlist.size - 1)
                correction?.let { put("correction", it) }
                put("sheets", JSONArray(encodeSheets(sheets)))
            }.toString().toByteArray()

            // Phones intermittently drop large uploads mid-flight; retry with a
            // fresh connection, matching the iOS client.
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    return@withContext post(body, shortlist.size, bookCount)
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < 3) delay(2000)
                }
            }
            throw lastError ?: IOException("judge request never attempted")
        }

    private fun post(body: ByteArray, candidateCount: Int, bookCount: Int): JudgeResult {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 290_000
        conn.setRequestProperty("content-type", "application/json")
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        require(code in 200..299) { "judge server $code: ${responseText.take(400)}" }

        val root = JSONObject(responseText)
        val book = root.getJSONObject("book")
        val usage = root.optJSONObject("usage") ?: JSONObject()
        val selectionsArr = book.getJSONArray("selections")
        val selections = (0 until selectionsArr.length()).map { i ->
            val s = selectionsArr.getJSONObject(i)
            Selection(s.getInt("index"), s.optInt("page", i + 1))
        }

        // The server validates too; keep the client-side checks as a belt-and-suspenders.
        val indexes = selections.map { it.index }
        require(selections.size == bookCount) { "Judge returned ${selections.size} selections, expected $bookCount" }
        require(indexes.toSet().size == bookCount) { "Judge returned duplicate indexes" }
        require(indexes.all { it in 0 until candidateCount }) {
            "Judge returned out-of-range index (candidates 0..${candidateCount - 1})"
        }

        return JudgeResult(
            title = book.optString("title", "Untitled Month"),
            coverIndex = book.optInt("cover_index", indexes.first()),
            selections = selections.sortedBy { it.page },
            inputTokens = usage.optInt("input_tokens", 0),
            outputTokens = usage.optInt("output_tokens", 0),
        )
    }
}
