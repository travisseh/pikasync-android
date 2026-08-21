package com.travisse.pikasync.pipeline

import android.graphics.Bitmap
import android.util.Base64
import com.travisse.pikasync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 7: POST contact sheets to the Anthropic Messages API and have
 * claude-sonnet-5 pick exactly 20 photos for the monthly book.
 */
object Judge {
    private const val MODEL = "claude-sonnet-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    suspend fun judge(sheets: List<Bitmap>, shortlist: List<PhotoItem>): JudgeResult =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.ANTHROPIC_API_KEY
            require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY missing from local.properties" }

            val prompt = """
                You are choosing photos for a printed monthly family photobook.
                The attached contact sheets show ${shortlist.size} candidate photos. Each photo is
                labeled [index] with its date and detected face count.
                Choose EXACTLY 20 photos. Build a chronological arc across the month,
                balance the people who appear, include 3-5 non-people shots (places,
                food, details), and never pick two photos of the same scene.
                Respond ONLY with JSON, no prose, in this exact shape:
                {"title": "...", "cover_index": N, "selections": [{"index": N, "page": N, "caption": "..."}]}
            """.trimIndent()

            val content = JSONArray()
            sheets.forEach { sheet ->
                val jpeg = ByteArrayOutputStream().also {
                    sheet.compress(Bitmap.CompressFormat.JPEG, 70, it)
                }.toByteArray()
                content.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    })
                })
            }
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 4000)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                }))
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            require(code in 200..299) { "Anthropic API error $code: ${responseText.take(400)}" }

            parse(responseText, shortlist.size)
        }

    private fun parse(responseText: String, candidateCount: Int): JudgeResult {
        val root = JSONObject(responseText)
        val usage = root.getJSONObject("usage")
        val text = root.getJSONArray("content").let { blocks ->
            (0 until blocks.length())
                .map { blocks.getJSONObject(it) }
                .firstOrNull { it.getString("type") == "text" }
                ?.getString("text") ?: ""
        }
        // model was told JSON-only, but strip code fences defensively
        val json = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .let { it.substring(it.indexOf('{')) } // tolerate leading prose

        val parsed = JSONObject(json)
        val selectionsArr = parsed.getJSONArray("selections")
        val selections = (0 until selectionsArr.length()).map { i ->
            val s = selectionsArr.getJSONObject(i)
            Selection(s.getInt("index"), s.optInt("page", i + 1), s.optString("caption", ""))
        }

        // Validate: exactly 20 distinct in-range indexes
        val indexes = selections.map { it.index }
        require(selections.size == 20) { "Judge returned ${selections.size} selections, expected 20" }
        require(indexes.toSet().size == 20) { "Judge returned duplicate indexes" }
        require(indexes.all { it in 0 until candidateCount }) {
            "Judge returned out-of-range index (candidates 0..${candidateCount - 1})"
        }

        return JudgeResult(
            title = parsed.optString("title", "Untitled Month"),
            coverIndex = parsed.optInt("cover_index", indexes.first()),
            selections = selections.sortedBy { it.page },
            inputTokens = usage.optInt("input_tokens", 0),
            outputTokens = usage.optInt("output_tokens", 0),
        )
    }
}
