package com.travisse.pikasync.pipeline

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * First-run setup: walk the last 6 months of photos, detect + embed faces,
 * and populate PeopleStore so the user can name/star their family before
 * generating books. Mirror of the iOS PeopleScanner.
 */
object PeopleScanner {
    var scanning by mutableStateOf(false); private set
    var progress by mutableDoubleStateOf(0.0); private set
    var statusText by mutableStateOf(""); private set
    var peopleVersion by mutableIntStateOf(0); private set

    private const val SCANNED_KEY = "peopleScanDone"

    fun hasScanned(context: Context): Boolean =
        context.getSharedPreferences("people", Context.MODE_PRIVATE).getBoolean(SCANNED_KEY, false)

    fun notifyPeopleChanged() { peopleVersion++ }

    /** monthsBack drives the deep scan (People tab); limitCount overrides it for the fast onboarding scan (most recent N photos, any date). */
    suspend fun scan(context: Context, monthsBack: Int = 6, limitCount: Int? = null) {
        if (scanning) return
        scanning = true
        try {
            withContext(Dispatchers.Default) { scanInner(context, monthsBack, limitCount) }
        } finally {
            scanning = false
        }
    }

    private suspend fun scanInner(context: Context, monthsBack: Int, limitCount: Int? = null) {
        data class Row(val id: Long, val bucket: String, val path: String)
        val rows = mutableListOf<Row>()
        // Deep scan (People tab) windows by date; the onboarding scan instead
        // takes the most recent N photos regardless of date so huge or sparse
        // libraries both finish fast. Recency = DATE_TAKEN (EXIF) descending,
        // photos missing DATE_TAKEN sort last (DATE_ADDED alone is wrong for
        // synced/restored libraries).
        val selection: String?
        val selectionArgs: Array<String>?
        if (limitCount != null) {
            selection = null
            selectionArgs = null
        } else {
            val start = Calendar.getInstance().apply { add(Calendar.MONTH, -monthsBack) }
            selection = "(${MediaStore.Images.Media.DATE_TAKEN} >= ?) OR " +
                "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ?)"
            selectionArgs = arrayOf(start.timeInMillis.toString(), (start.timeInMillis / 1000).toString())
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
            ),
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                if (limitCount != null && rows.size >= limitCount) break
                val hay = "${c.getString(bCol) ?: ""} ${c.getString(pCol) ?: ""}".lowercase()
                if (listOf("screenshots", "whatsapp", "download", "telegram").any { hay.contains(it) }) continue
                rows += Row(c.getLong(idCol), c.getString(bCol) ?: "", c.getString(pCol) ?: "")
            }
        }

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        try {
            for ((i, row) in rows.withIndex()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
                val bmp: Bitmap = try {
                    context.contentResolver.loadThumbnail(uri, Size(900, 900), null)
                } catch (_: Exception) { continue }
                try {
                    val faces = detector.process(InputImage.fromBitmap(bmp, 0)).await()
                    for (face in faces.filter { FaceEmbedder.eligible(it, bmp.width) }.take(6)) {
                        val crop = FaceEmbedder.alignedCrop(bmp, face) ?: continue
                        val emb = FaceEmbedder.embed(context, crop) ?: continue
                        val png = ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        PeopleStore.assign(context, emb, png)
                        crop.recycle()
                    }
                } catch (_: Exception) { /* per-photo best-effort */ }
                bmp.recycle()
                if (i % 10 == 0) {
                    progress = (i + 1).toDouble() / maxOf(1, rows.size)
                    statusText = "Scanning faces ${i + 1}/${rows.size}"
                }
            }
        } finally {
            detector.close()
        }
        PeopleStore.save(context)
        context.getSharedPreferences("people", Context.MODE_PRIVATE)
            .edit().putBoolean(SCANNED_KEY, true).apply()
        statusText = "Found ${PeopleStore.load(context).size} people in ${rows.size} photos"
        progress = 1.0
        notifyPeopleChanged()
    }
}
