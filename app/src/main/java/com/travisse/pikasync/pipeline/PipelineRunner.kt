package com.travisse.pikasync.pipeline

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import kotlin.math.min

/**
 * On-device photobook pipeline, mirror of the iOS Pipeline stages:
 * ingest -> score -> burst dedup -> rank -> coverage shortlist -> contact sheets -> judge.
 */
class PipelineRunner(private val context: Context) {

    private val utilityFolders = listOf("screenshots", "whatsapp", "download", "telegram")

    suspend fun run(
        year: Int,
        month: Int, // 1-12
        onStage: (StageTiming) -> Unit,
    ): PipelineResult {
        val timings = mutableListOf<StageTiming>()
        fun <T> stage(name: String, detail: (T) -> String, block: () -> T): T {
            val t0 = System.currentTimeMillis()
            val out = block()
            val timing = StageTiming(name, System.currentTimeMillis() - t0, detail(out))
            timings += timing
            onStage(timing)
            return out
        }
        suspend fun <T> stageS(name: String, detail: (T) -> String, block: suspend () -> T): T {
            val t0 = System.currentTimeMillis()
            val out = block()
            val timing = StageTiming(name, System.currentTimeMillis() - t0, detail(out))
            timings += timing
            onStage(timing)
            return out
        }

        try {
            // 1. ingest
            val all = stage("1 ingest", { l: List<PhotoItem> -> "${l.size} photos" }) {
                ingest(year, month)
            }
            if (all.isEmpty()) return PipelineResult(timings, emptyList(), emptyList(), null, "No photos found for that month")

            // 2. score (utility exclusion + ML Kit faces + Laplacian sharpness + aHash)
            val scored = stageS("2 score", { l: List<PhotoItem> -> "${l.size} kept, ${all.size - l.size} utility dropped" }) {
                score(all)
            }
            if (scored.isEmpty()) return PipelineResult(timings, emptyList(), emptyList(), null, "All photos excluded as utility images")

            // 3. burst dedup
            val deduped = stage("3 burst dedup", { l: List<PhotoItem> -> "${scored.size} -> ${l.size}" }) {
                dedup(scored)
            }

            // 4. rank
            val ranked = stage("4 rank", { _: List<PhotoItem> -> "composite = 0.6*sharp + 0.25*face + 0.1 any-face" }) {
                rank(deduped)
            }

            // 5. coverage shortlist of 48
            val shortlist = stage("5 shortlist", { l: List<PhotoItem> -> "${l.size} picked (per-week floors, 7 no-face seats)" }) {
                shortlist(ranked)
            }

            // 6. contact sheets
            val sheets = stage("6 contact sheets", { l: List<Bitmap> -> "${l.size} sheets (4x4, 420px cells)" }) {
                ContactSheet.render(context, shortlist)
            }

            // 7. judge
            val judge = stageS("7 judge", { j: JudgeResult ->
                "20 picks, ${j.inputTokens} in / ${j.outputTokens} out tokens"
            }) {
                Judge.judge(sheets, shortlist)
            }

            return PipelineResult(timings, shortlist, sheets, judge, null)
        } catch (e: Exception) {
            return PipelineResult(timings, emptyList(), emptyList(), null, e.message ?: e.toString())
        }
    }

    // MARK: stage 1 — ingest month's images from MediaStore

    private fun ingest(year: Int, month: Int): List<PhotoItem> {
        val start = Calendar.getInstance().apply {
            set(year, month - 1, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val startSec = start.timeInMillis / 1000
        val endSec = end.timeInMillis / 1000

        val items = mutableListOf<PhotoItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
            ),
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?",
            arrayOf(startSec.toString(), endSec.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = if (!c.isNull(takenCol) && c.getLong(takenCol) > 0) c.getLong(takenCol)
                            else c.getLong(addedCol) * 1000
                items += PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    takenAtMs = taken,
                    bucket = c.getString(bucketCol) ?: "",
                    relativePath = c.getString(pathCol) ?: "",
                )
            }
        }
        return items
    }

    // MARK: stage 2 — score

    private suspend fun score(items: List<PhotoItem>): List<PhotoItem> {
        val kept = items.filter { p ->
            val hay = "${p.bucket} ${p.relativePath}".lowercase()
            utilityFolders.none { hay.contains(it) }
        }
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
        try {
            for (p in kept) {
                val bmp = loadThumb(p, 512) ?: continue
                // faces
                try {
                    val faces = detector.process(InputImage.fromBitmap(bmp, 0)).await()
                    p.faceCount = faces.size
                    if (faces.isNotEmpty()) {
                        val perFace = faces.map { f ->
                            val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability)
                                .map(Float::toDouble).ifEmpty { listOf(0.5) }.average()
                            val smile = f.smilingProbability?.toDouble() ?: 0.5
                            0.6 * eyes + 0.4 * smile
                        }.average()
                        // presence of more faces nudges quality up a little
                        p.faceQuality = (0.85 * perFace + 0.15 * min(faces.size, 3) / 3.0).coerceIn(0.0, 1.0)
                    }
                } catch (_: Exception) { /* face scoring is best-effort */ }
                // sharpness + aHash on grayscale downscales
                val gray = grayscale(bmp, 128)
                p.sharpness = laplacianVariance(gray.first, gray.second, gray.third)
                p.aHash = averageHash(bmp)
                bmp.recycle()
            }
        } finally {
            detector.close()
        }
        return kept
    }

    private fun loadThumb(p: PhotoItem, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(p.uri, Size(size, size), null)
    } catch (_: Exception) { null }

    /** Returns (pixels, width, height) of a downscaled grayscale image. Pure Kotlin, no OpenCV. */
    private fun grayscale(src: Bitmap, targetW: Int): Triple<DoubleArray, Int, Int> {
        val w = min(targetW, src.width)
        val h = (src.height.toDouble() * w / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        val gray = DoubleArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        return Triple(gray, w, h)
    }

    /** Variance of the 4-neighbor Laplacian — the standard cheap blur metric. */
    private fun laplacianVariance(g: DoubleArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
                sum += lap; sumSq += lap * lap; n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** 8x8 average hash for near-duplicate detection. */
    private fun averageHash(src: Bitmap): Long {
        val tiny = Bitmap.createScaledBitmap(src, 8, 8, true)
        val px = IntArray(64)
        tiny.getPixels(px, 0, 8, 0, 0, 8, 8)
        tiny.recycle()
        val gray = DoubleArray(64) { i ->
            val c = px[i]
            0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        val mean = gray.average()
        var hash = 0L
        for (i in 0 until 64) if (gray[i] > mean) hash = hash or (1L shl i)
        return hash
    }

    // MARK: stage 3 — burst dedup (within 90s AND aHash Hamming distance <= 5, keep best-scored)

    private fun dedup(items: List<PhotoItem>): List<PhotoItem> {
        val sorted = items.sortedBy { it.takenAtMs }
        val groups = mutableListOf<MutableList<PhotoItem>>()
        for (p in sorted) {
            val g = groups.lastOrNull()
            val rep = g?.first()
            if (g != null && rep != null &&
                p.takenAtMs - g.last().takenAtMs <= 90_000 &&
                java.lang.Long.bitCount(p.aHash xor rep.aHash) <= 5
            ) {
                g.add(p)
            } else {
                groups.add(mutableListOf(p))
            }
        }
        // keep the best provisional (sharpness+face) photo in each burst group
        return groups.map { g -> g.maxBy { it.sharpness * (1.0 + it.faceQuality) } }
    }

    // MARK: stage 4 — rank by composite score

    private fun rank(items: List<PhotoItem>): List<PhotoItem> {
        val maxSharp = items.maxOfOrNull { it.sharpness } ?: 1.0
        val minSharp = items.minOfOrNull { it.sharpness } ?: 0.0
        val range = (maxSharp - minSharp).takeIf { it > 0 } ?: 1.0
        items.forEach { p ->
            val norm = (p.sharpness - minSharp) / range
            p.score = 0.6 * norm + 0.25 * p.faceQuality + (if (p.faceCount > 0) 0.1 else 0.0)
        }
        return items.sortedByDescending { it.score }
    }

    // MARK: stage 5 — coverage shortlist of 48 (per-week floors, 7 protected no-face seats)

    private fun shortlist(ranked: List<PhotoItem>): List<PhotoItem> {
        val target = 48
        if (ranked.size <= target) return ranked.sortedBy { it.takenAtMs }

        val picked = LinkedHashSet<PhotoItem>()
        val byWeek = ranked.groupBy { p ->
            Calendar.getInstance().apply { timeInMillis = p.takenAtMs }.get(Calendar.WEEK_OF_YEAR)
        }
        // per-week floor so no week of the month goes unrepresented
        val floor = ((target - 7) / byWeek.size).coerceAtLeast(1)
        for ((_, weekPhotos) in byWeek) {
            weekPhotos.sortedByDescending { it.score }.take(floor).forEach { picked.add(it) }
        }
        // 7 protected seats for the best no-face shots (scenery, food, places)
        ranked.filter { it.faceCount == 0 && it !in picked }
            .take(7 - picked.count { it.faceCount == 0 }.coerceAtMost(7))
            .forEach { picked.add(it) }
        // fill the remainder by global score
        for (p in ranked) {
            if (picked.size >= target) break
            picked.add(p)
        }
        return picked.take(target).sortedBy { it.takenAtMs }
    }
}
