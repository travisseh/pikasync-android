package com.travisse.pikasync.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kotlin port of the iOS ShareClient: uploads a saved book to the
 * pikabook-share Convex backend and returns the public share link.
 * Flow: /create-book -> POST each page JPEG to its storage upload URL ->
 * /finalize-book. The shareId doubles as the feedback token.
 */
object ShareClient {
    private const val CONVEX_SITE = "https://silent-marmot-268.convex.site"
    private const val SHARE_BASE = "https://pikabook-share.vercel.app/b/"

    data class ShareResult(val url: String, val shareId: String)

    suspend fun upload(
        context: Context,
        run: SavedRun,
        onProgress: (String) -> Unit = {},
    ): ShareResult = withContext(Dispatchers.IO) {
        // Page 0 is the cover; book pages follow in order.
        val ordered = listOf(run.coverUri) + run.selections.sortedBy { it.page }.map { it.uri }

        onProgress("creating book…")
        val create = postJson(
            "/create-book",
            JSONObject()
                .put("title", run.title)
                .put("monthLabel", run.monthLabel)
                .put("deviceName", android.os.Build.MODEL ?: "Android")
                .put("pageCount", ordered.size)
        )
        val shareId = create.getString("shareId")
        val bookId = create.getString("bookId")
        val uploadUrls = create.getJSONArray("uploadUrls")

        val pages = JSONArray()
        ordered.forEachIndexed { i, uriStr ->
            onProgress("uploading ${i + 1}/${ordered.size}…")
            val jpeg = loadJpeg(context, Uri.parse(uriStr))
                ?: throw IllegalStateException("couldn't load photo for page $i")
            val conn = URL(uploadUrls.getString(i)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("content-type", "image/jpeg")
            conn.outputStream.use { it.write(jpeg) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code != 200) throw IllegalStateException("page upload failed ($code)")
            pages.put(
                JSONObject().put("page", i).put("storageId", JSONObject(body).getString("storageId"))
            )
        }

        onProgress("finalizing…")
        postJson("/finalize-book", JSONObject().put("bookId", bookId).put("pages", pages))
        ShareResult(SHARE_BASE + shareId, shareId)
    }

    /** In-app feedback lands in the same Convex table the web viewers write to. */
    suspend fun sendFeedback(
        shareId: String,
        page: Int?,
        reaction: String?,
        text: String?,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("shareId", shareId).put("author", "Travisse (Android)")
        if (page != null) body.put("page", page)
        if (reaction != null) body.put("reaction", reaction)
        if (!text.isNullOrBlank()) body.put("text", text)
        postJson("/feedback", body)
    }

    /**
     * Load a photo at share resolution with orientation baked into the pixels —
     * ImageDecoder applies the EXIF rotation while decoding, so the encoded
     * JPEG is always upright (the web viewer saw sideways photos otherwise).
     */
    private fun loadJpeg(context: Context, uri: Uri, maxDim: Int = 1600): ByteArray? = try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxDim.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        // Transient resets happen on mobile networks; retry with a fresh connection.
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URL(CONVEX_SITE + path).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code != 200) throw IllegalStateException("share server $code: ${text.take(200)}")
                return JSONObject(text)
            } catch (e: java.io.IOException) {
                last = e
                if (attempt < 2) Thread.sleep(2_000)
            }
        }
        throw last ?: IllegalStateException("share request never attempted")
    }
}
