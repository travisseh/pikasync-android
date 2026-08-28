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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
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
        trigger: String = "manual",
        onStage: (StageTiming) -> Unit,
    ): PipelineResult {
        val monthKey = String.format(java.util.Locale.US, "%04d-%02d", year, month)
        val result = runInner(year, month, onStage)
        if (result.error == null) {
            RunStore.fromResult(year, month, result, trigger)?.let { run ->
                RunStore.add(context, run)
                autoShare(run, onStage)
            }
        }
        RunStatusLog.write(
            context,
            month = monthKey,
            status = if (result.error == null) "done — ${result.judge?.selections?.size ?: 0}-photo book ready" else "failed",
            error = result.error,
            stages = result.timings.map { "${it.name}: ${it.detail} (${it.ms}ms)" },
            trigger = trigger,
        )
        return result
    }

    /**
     * Auto-share every finished book so it's immediately feedback-able
     * (interactive and background paths both come through run()). Best-effort:
     * on failure the run is marked needsShare and retried on next app open.
     */
    private suspend fun autoShare(run: SavedRun, onStage: (StageTiming) -> Unit) {
        val t0 = System.currentTimeMillis()
        try {
            val share = ShareClient.upload(context, run)
            RunStore.update(context, run.copy(shareId = share.shareId, shareUrl = share.url, needsShare = false))
            onStage(StageTiming("8 share", System.currentTimeMillis() - t0, share.url))
        } catch (e: Exception) {
            RunStore.update(context, run.copy(needsShare = true))
            onStage(StageTiming("8 share", System.currentTimeMillis() - t0, "share failed (will retry): ${e.message?.take(80)}"))
        }
    }

    private suspend fun runInner(
        year: Int,
        month: Int,
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

            // 2. score (utility exclusion + document gate + ML Kit faces + identity + sharpness + aHash)
            val excludedIds = PeopleStore.excludedIds(context)
            var excludedDropped = 0
            val scored = stageS("2 score", { r: ScoreResult ->
                "${r.kept.size} kept, ${r.utilityDropped} utility + ${r.docsDropped} docs + $excludedDropped excluded-person dropped"
            }) {
                val r = score(all)
                // excluded-people drop (mirror of iOS gate stage)
                val kept = r.kept.filter { p ->
                    val hit = p.personIds.any(excludedIds::contains)
                    if (hit) excludedDropped++
                    !hit
                }
                ScoreResult(kept, r.utilityDropped, r.docsDropped)
            }.kept
            if (scored.isEmpty()) return PipelineResult(timings, emptyList(), emptyList(), null, "All photos excluded as utility/document images")
            PeopleStore.save(context)  // persist centroid updates from the identity pass

            // 3. burst dedup
            val deduped = stage("3 burst dedup", { l: List<PhotoItem> -> "${scored.size} -> ${l.size}" }) {
                dedup(scored)
            }

            // 4. rank
            val ranked = stage("4 rank", { _: List<PhotoItem> -> "composite = 0.6*sharp + 0.25*face + 0.1 any-face" }) {
                rank(deduped)
            }

            // 5. coverage shortlist of 48
            val starredCount = PeopleStore.requiredIds(context).size
            val rawShortlist = stage("5 shortlist", { l: List<PhotoItem> ->
                val strangers = ranked.size - l.size
                "${l.size} picked" +
                    (if (starredCount > 0) " ($strangers strangers/rank dropped, $starredCount starred people active)"
                     else " (per-week floors, 7 no-face seats)")
            }) {
                shortlist(ranked)
            }

            // 5b. pre-judge scene collapse: cluster the shortlist by scene and pass at
            // most 3 representatives per cluster, so the judge can never be forced to
            // pick near-duplicates (round-3 fix; this alone fixed May on iOS/Mac).
            var sceneClusterCount = 0
            val shortlist = stage("5b scene collapse", { l: List<PhotoItem> ->
                "${rawShortlist.size - l.size} scene-collapsed, $sceneClusterCount scenes"
            }) {
                val clusters = sceneClusters(rawShortlist)
                sceneClusterCount = clusters.size
                clusters.flatMap { idxs ->
                    idxs.map { rawShortlist[it] }.sortedByDescending { it.score }.take(3)
                }.sortedBy { it.takenAtMs }
            }

            // 6. contact sheets
            val sheets = stage("6 contact sheets", { l: List<Bitmap> -> "${l.size} sheets (4x4, 420px cells)" }) {
                ContactSheet.render(context, shortlist)
            }

            // 7. judge — book size capped by sessions and distinct scenes so thin
            // months shrink instead of padding with duplicates (iOS formula).
            val sessions = sessionCount(shortlist)
            val bookCount = maxOf(4, minOf(Judge.bookCount(shortlist.size), maxOf(6, 2 * sessions), sceneClusterCount))
            val monthLabel = SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
                .format(Calendar.getInstance().apply { set(year, month - 1, 1) }.time)
            var residualPairs = 0
            val judge = stageS("7 judge", { j: JudgeResult ->
                "${j.selections.size} picks" +
                    (when (residualPairs) {
                        0 -> ""
                        1 -> ", 1 residual pair (tolerated — likely major event)"
                        else -> ", $residualPairs pairs still similar (accepted)"
                    }) +
                    ", ${j.inputTokens} in / ${j.outputTokens} out tokens"
            }) {
                var result = Judge.judge(sheets, shortlist, bookCount, monthLabel)
                val violations = sameScenePairs(result, shortlist)
                residualPairs = violations.size
                // One residual pair is tolerated: major events (birthday, big trip)
                // legitimately carry a second page of the same session. Retry only
                // when the book has 2+ same-scene pairs.
                if (violations.size >= 2) {
                    val correction = "IMPORTANT CORRECTION — your previous answer had these " +
                        "problems, fix them while keeping everything else: " +
                        violations.joinToString("; ") { (a, b) ->
                            "picks $a and $b are the same scene — replace one of each pair"
                        }
                    val retry = Judge.judge(sheets, shortlist, bookCount, monthLabel, correction)
                    // accept the retry either way; surface anything still similar
                    residualPairs = sameScenePairs(retry, shortlist).size
                    result = JudgeResult(
                        title = retry.title,
                        coverIndex = retry.coverIndex,
                        selections = retry.selections,
                        inputTokens = result.inputTokens + retry.inputTokens,
                        outputTokens = result.outputTokens + retry.outputTokens,
                    )
                }
                result
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
            // month = when the photo was TAKEN (EXIF), falling back to DATE_ADDED
            // for rows with no datetaken (synced/restored photos keep their real month)
            "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
                "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)",
            arrayOf(start.timeInMillis.toString(), end.timeInMillis.toString(),
                    startSec.toString(), endSec.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
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

    data class ScoreResult(val kept: List<PhotoItem>, val utilityDropped: Int, val docsDropped: Int)

    private suspend fun score(items: List<PhotoItem>): ScoreResult {
        // cheap gate first: utility folders by MediaStore metadata (free)
        val survivors = items.filter { p ->
            val hay = "${p.bucket} ${p.relativePath}".lowercase()
            utilityFolders.none { hay.contains(it) }
        }
        val utilityDropped = items.size - survivors.size
        var docsDropped = 0

        val cached = ScoreCache.load(context)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
        // second detector with landmarks for the identity-embedding pass
        val landmarkDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val kept = mutableListOf<PhotoItem>()
        try {
            for (p in survivors) {
                // pre-scored (onboarding or an earlier run): reuse and skip decoding
                val hit = cached[p.id]
                if (hit != null) {
                    if (hit.isDoc) { docsDropped++; continue }
                    p.faceCount = hit.faceCount
                    p.faceQuality = hit.faceQuality
                    p.sharpness = hit.sharpness
                    p.aHash = hit.aHash
                    p.personIds = hit.personIds
                    kept += p
                    continue
                }
                val isDoc = scoreOne(p, detector, landmarkDetector, textRecognizer)
                if (isDoc == null) continue  // undecodable
                ScoreCache.put(context, p.id, ScoreCache.Entry(
                    p.faceCount, p.faceQuality, p.sharpness, p.aHash, p.personIds,
                    isDoc, System.currentTimeMillis(),
                ))
                if (isDoc) { docsDropped++; continue }
                kept += p
            }
        } finally {
            detector.close()
            landmarkDetector.close()
            textRecognizer.close()
        }
        ScoreCache.save(context)
        return ScoreResult(kept, utilityDropped, docsDropped)
    }

    /**
     * The full per-photo scoring path (document gate, ML Kit face quality,
     * identity embedding, sharpness, aHash) shared by pipeline runs and the
     * onboarding prescore. Returns true if the photo is a document (drop),
     * false if scored and kept, null if it couldn't be decoded.
     */
    suspend fun scoreOne(
        p: PhotoItem,
        detector: com.google.mlkit.vision.face.FaceDetector,
        landmarkDetector: com.google.mlkit.vision.face.FaceDetector,
        textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): Boolean? {
        val bmp = loadThumb(p, 512) ?: return null
        // document gate: catches camera photos of letters/receipts/whiteboards
        // that folder exclusion can't see. Runs only on folder-gate survivors.
        if (isDocument(textRecognizer, bmp, p)) {
            bmp.recycle()
            return true
        }
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
        // face IDENTITY: embed eligible faces on a 900px decode and assign
        // to PeopleStore clusters (auto-creates clusters for new people,
        // exactly like iOS IncrementalScorer). Best-effort.
        if (p.faceCount > 0) {
            try {
                val big = loadThumb(p, 900)
                if (big != null) {
                    val faces = landmarkDetector.process(InputImage.fromBitmap(big, 0)).await()
                    val ids = mutableSetOf<String>()
                    for (f in faces.filter { FaceEmbedder.eligible(it, big.width) }.take(6)) {
                        val crop = FaceEmbedder.alignedCrop(big, f) ?: continue
                        val emb = FaceEmbedder.embed(context, crop)
                        crop.recycle()
                        if (emb != null) ids += PeopleStore.assign(context, emb, null)
                    }
                    p.personIds = ids
                    big.recycle()
                }
            } catch (_: Exception) { /* identity is best-effort */ }
        }
        // sharpness + aHash on grayscale downscales
        val gray = grayscale(bmp, 128)
        p.sharpness = laplacianVariance(gray.first, gray.second, gray.third)
        p.aHash = averageHash(bmp)
        bmp.recycle()
        return false
    }

    /**
     * Onboarding prescore: run the shared scoring path over LAST month's photos
     * and fill the score cache so "Make my first book" starts partially
     * pre-scored. Safe to run while the user sits on the people grid; skipped
     * if a book build is already running (they share PeopleStore writes).
     */
    suspend fun prescoreMonth(year: Int, month: Int, shouldStop: () -> Boolean = { false }) {
        val items = ingest(year, month)
        if (items.isEmpty()) return
        val cached = ScoreCache.load(context)
        val todo = items.filter { p ->
            cached[p.id] == null && utilityFolders.none { "${p.bucket} ${p.relativePath}".lowercase().contains(it) }
        }
        if (todo.isEmpty()) return
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
        val landmarkDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for ((i, p) in todo.withIndex()) {
                if (shouldStop()) break
                val isDoc = scoreOne(p, detector, landmarkDetector, textRecognizer) ?: continue
                ScoreCache.put(context, p.id, ScoreCache.Entry(
                    p.faceCount, p.faceQuality, p.sharpness, p.aHash, p.personIds,
                    isDoc, System.currentTimeMillis(),
                ))
                if (i % 20 == 0) ScoreCache.save(context)
            }
        } finally {
            detector.close()
            landmarkDetector.close()
            textRecognizer.close()
        }
        ScoreCache.save(context)
        PeopleStore.save(context)
    }

    /**
     * Document signal from ML Kit text recognition on the already-decoded 512px bitmap.
     * A photo is dropped as a document when:
     *  - it has 9+ text blocks covering more than 15% of the image area
     *    (close-up receipts, whiteboards where text resolves large), OR
     *  - it has 10+ text blocks covering at least 5% of the image area
     *    (a full-page letter at 512px: only headline-size text resolves, as many
     *    sparse one-line blocks — measured on a real IRS letter: 12 blocks, 6.2%
     *    coverage, while no ordinary photo in the test set exceeded 2 blocks), OR
     *  - any single paragraph-like column: a block with 5+ lines whose bounding box
     *    covers at least 8% of the image area (a dense readable letter body).
     * Street signs and t-shirt logos stay: 1-2 blocks, tiny coverage, no dense column.
     */
    private suspend fun isDocument(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bmp: android.graphics.Bitmap,
        p: PhotoItem,
    ): Boolean {
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            val imageArea = bmp.width.toDouble() * bmp.height
            if (imageArea <= 0 || text.textBlocks.isEmpty()) return false
            var coveredArea = 0.0
            var denseColumn = false
            val lineCounts = mutableListOf<Int>()
            for (block in text.textBlocks) {
                val box = block.boundingBox ?: continue
                val area = box.width().toDouble() * box.height()
                coveredArea += area
                lineCounts += block.lines.size
                if (block.lines.size >= 5 && area / imageArea >= 0.08) denseColumn = true
            }
            val coverage = (coveredArea / imageArea).coerceAtMost(1.0)
            val blocks = text.textBlocks.size
            val doc = (blocks >= 9 && coverage > 0.15) ||
                (blocks >= 10 && coverage >= 0.05) ||
                denseColumn
            if (doc) {
                android.util.Log.d(
                    "DocGate",
                    "id=${p.id} DROP blocks=$blocks lines=$lineCounts " +
                        "coverage=${"%.3f".format(coverage)} dense=$denseColumn"
                )
            }
            doc
        } catch (e: Exception) {
            android.util.Log.e("DocGate", "id=${p.id} recognizer FAILED: $e")
            false // text recognition is a best-effort gate; never drop a photo on failure
        }
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

    /**
     * Deterministic post-judge scene check: two picks violate same-scene diversity when
     * taken within 6 hours of each other AND near-duplicate by aHash (Hamming <= 8,
     * slightly looser than the burst threshold since these survived shortlist).
     * Returns pairs of judge pick indexes (into the shortlist).
     * TODO: aHash is the tonight-approximation; parity with iOS Vision feature prints
     * comes via the MediaPipe image embedder later.
     */
    private fun sameScenePairs(result: JudgeResult, shortlist: List<PhotoItem>): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        val picks = result.selections.map { it.index }
        for (i in picks.indices) {
            for (j in i + 1 until picks.size) {
                val a = shortlist[picks[i]]
                val b = shortlist[picks[j]]
                val within6h = kotlin.math.abs(a.takenAtMs - b.takenAtMs) <= 6 * 3600 * 1000L
                val nearDup = java.lang.Long.bitCount(a.aHash xor b.aHash) <= 8
                if (within6h && nearDup) pairs += picks[i] to picks[j]
            }
        }
        return pairs
    }

    /**
     * Union-find scene clusters over the shortlist: same cluster when photos are
     * within 6 hours AND near-duplicate by aHash (Hamming <= 8 — same signal as
     * the post-judge residual check). Mirror of the iOS sceneClusters().
     */
    private fun sceneClusters(photos: List<PhotoItem>): List<List<Int>> {
        val parent = IntArray(photos.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                val within6h = kotlin.math.abs(photos[i].takenAtMs - photos[j].takenAtMs) <= 6 * 3600 * 1000L
                val nearDup = java.lang.Long.bitCount(photos[i].aHash xor photos[j].aHash) <= 8
                if (within6h && nearDup) parent[find(j)] = find(i)
            }
        }
        return photos.indices.groupBy { find(it) }.values.toList()
    }

    /**
     * Sessions: 3h time-gap clusters, merged when any cross-session pair is
     * scene-similar (a two-location day still counts its outings separately,
     * but a resumed session at the same place merges). Mirror of iOS sessionCount().
     */
    private fun sessionCount(photos: List<PhotoItem>): Int {
        if (photos.isEmpty()) return 0
        val sorted = photos.sortedBy { it.takenAtMs }
        val sessions = mutableListOf<MutableList<PhotoItem>>()
        for (p in sorted) {
            val last = sessions.lastOrNull()
            if (last != null && p.takenAtMs - last.last().takenAtMs <= 3 * 3600 * 1000L) last.add(p)
            else sessions.add(mutableListOf(p))
        }
        // merge sessions that clearly share a scene
        val merged = mutableListOf<MutableList<PhotoItem>>()
        outer@ for (s in sessions) {
            for (m in merged) {
                for (a in m) for (b in s) {
                    if (java.lang.Long.bitCount(a.aHash xor b.aHash) <= 8) {
                        m.addAll(s); continue@outer
                    }
                }
            }
            merged.add(s)
        }
        return merged.size
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

        // When the book is "about" starred people, face-photos whose detected
        // people include NONE of the starred ones are strangers — drop them.
        // (Photos with no eligible faces still qualify as texture shots.)
        val required = PeopleStore.requiredIds(context)
        val pool = if (required.isEmpty()) ranked else ranked.filter { p ->
            p.personIds.isEmpty() || p.personIds.any(required::contains)
        }
        if (pool.size <= target) return pool.sortedBy { it.takenAtMs }

        val picked = LinkedHashSet<PhotoItem>()
        val byWeek = pool.groupBy { p ->
            Calendar.getInstance().apply { timeInMillis = p.takenAtMs }.get(Calendar.WEEK_OF_YEAR)
        }
        // per-week floor so no week of the month goes unrepresented
        val floor = ((target - 7) / byWeek.size).coerceAtLeast(1)
        for ((_, weekPhotos) in byWeek) {
            weekPhotos.sortedByDescending { it.score }.take(floor).forEach { picked.add(it) }
        }
        // 7 protected seats for the best no-face shots (scenery, food, places)
        pool.filter { it.faceCount == 0 && it !in picked }
            .take(7 - picked.count { it.faceCount == 0 }.coerceAtMost(7))
            .forEach { picked.add(it) }
        // required-people guarantee: each starred person gets >=3 shortlist seats
        for (pid in required) {
            var have = picked.count { it.personIds.contains(pid) }
            for (p in pool) {
                if (have >= 3) break
                if (p.personIds.contains(pid) && picked.add(p)) have++
            }
        }
        // fill by rank, capping any single starred person at 45% of the shortlist
        fun overCap(p: PhotoItem): Boolean {
            if (p.faceCount == 0 || required.isEmpty()) return false
            for (pid in required) {
                if (!p.personIds.contains(pid)) continue
                val share = picked.count { it.personIds.contains(pid) }.toFloat() / maxOf(1, picked.size)
                if (share > 0.45f) return true
            }
            return false
        }
        for (p in pool) {
            if (picked.size >= target) break
            if (!overCap(p)) picked.add(p)
        }
        // backfill with the cap relaxed
        for (p in pool) {
            if (picked.size >= target) break
            picked.add(p)
        }
        return picked.take(target).sortedBy { it.takenAtMs }
    }
}
