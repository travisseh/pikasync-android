package com.travisse.pikasync.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ArcFace identity embedder — the SAME w600k_mbf ONNX weights the iOS Core ML
 * port was converted from, run via ONNX Runtime, so embeddings/centroids are
 * directly comparable across platforms. Input: aligned 112x112 RGB crop.
 * Output: 512-dim L2-normalized embedding. Alignment mirrors the iOS
 * FaceEmbedder: canonical 5-point template, no-reflection similarity
 * (Umeyama) fit, both eye assignments tried, eyes-only fallback at residual > 8px.
 */
object FaceEmbedder {
    private const val SIZE = 112

    // Canonical ArcFace 112x112 5-point template (identical to iOS).
    private val TEMPLATE = listOf(
        PointF(38.2946f, 51.6963f),  // left eye (image-left)
        PointF(73.5318f, 51.5014f),  // right eye
        PointF(56.0252f, 71.7366f),  // nose
        PointF(41.5493f, 92.3655f),  // mouth left
        PointF(70.7299f, 92.2041f),  // mouth right
    )

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null

    private fun session(context: Context): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val bytes = context.assets.open("w600k_mbf.onnx").readBytes()
            return env.createSession(bytes).also { session = it }
        }
    }

    /**
     * Face eligible for identity embedding — Android analog of the iOS gates
     * (bbox width >= 5% of image, landmarks present, capture quality >= 0.15).
     * ML Kit has no captureQuality; a strong yaw bound plays that role.
     */
    fun eligible(face: Face, imageWidth: Int): Boolean {
        if (face.boundingBox.width() < 0.05 * imageWidth) return false
        if (face.getLandmark(FaceLandmark.LEFT_EYE) == null) return false
        if (face.getLandmark(FaceLandmark.RIGHT_EYE) == null) return false
        return abs(face.headEulerAngleY) <= 45f
    }

    /** Aligned 112x112 crop from ML Kit landmarks, or null. */
    fun alignedCrop(fullImage: Bitmap, face: Face): Bitmap? {
        val eyeA = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val eyeB = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        // ML Kit's left/right are the subject's; the face may be rolled. Try
        // both eye-to-template assignments, keep the lower-residual fit — the
        // mirrored assignment can't be fit by a rotation-only similarity.
        fun build(eyeL: PointF, eyeR: PointF, mL: PointF?, mR: PointF?): Pair<Matrix, Float>? {
            val src = mutableListOf(eyeL, eyeR)
            val dst = mutableListOf(TEMPLATE[0], TEMPLATE[1])
            if (nose != null) { src.add(nose); dst.add(TEMPLATE[2]) }
            if (mL != null && mR != null) {
                src.add(mL); dst.add(TEMPLATE[3])
                src.add(mR); dst.add(TEMPLATE[4])
            }
            val t = similarityTransform(src, dst) ?: return null
            var residual = 0f
            val pt = FloatArray(2)
            for (i in src.indices) {
                pt[0] = src[i].x; pt[1] = src[i].y
                t.mapPoints(pt)
                residual += hypot(pt[0] - dst[i].x, pt[1] - dst[i].y)
            }
            return t to residual / src.size
        }

        val candidates = listOfNotNull(
            build(eyeA, eyeB, mouthL, mouthR),
            // mirrored eye assignment: mouth corners swap too
            build(eyeB, eyeA, mouthR, mouthL),
        )
        var best = candidates.minByOrNull { it.second } ?: return null

        // Landmarks disagree badly (occlusion/extreme pose): exact eyes-only
        // fit, disambiguating the mirror with the nose (must land below eyes).
        if (best.second > 8f) {
            val eyesOnly = listOfNotNull(
                similarityTransform(listOf(eyeA, eyeB), listOf(TEMPLATE[0], TEMPLATE[1])),
                similarityTransform(listOf(eyeB, eyeA), listOf(TEMPLATE[0], TEMPLATE[1])),
            )
            val pick = eyesOnly.firstOrNull { t ->
                if (nose == null) return@firstOrNull true
                val pt = floatArrayOf(nose.x, nose.y)
                t.mapPoints(pt)
                pt[1] > TEMPLATE[0].y
            } ?: eyesOnly.firstOrNull()
            if (pick != null) best = pick to 0f
        }

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(fullImage, best.first, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Embeds an aligned 112x112 crop -> 512-dim L2-normalized vector. */
    fun embed(context: Context, crop: Bitmap): FloatArray? {
        val sess = try { session(context) } catch (_: Exception) { return null }
        val px = IntArray(SIZE * SIZE)
        crop.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val n = SIZE * SIZE
        // RGB interleaved -> CHW float32, (p - 127.5) / 127.5 (identical to iOS)
        val chw = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = px[i]
            chw[i] = (Color.red(c) - 127.5f) / 127.5f
            chw[n + i] = (Color.green(c) - 127.5f) / 127.5f
            chw[2 * n + i] = (Color.blue(c) - 127.5f) / 127.5f
        }
        return try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
                sess.run(mapOf(sess.inputNames.first() to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = (out[0].value as Array<FloatArray>)[0]
                    val norm = sqrt(raw.fold(0f) { acc, v -> acc + v * v })
                    if (norm <= 0f) null else FloatArray(512) { raw[it] / norm }
                }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Least-squares similarity (rotation + uniform scale + translation, no
     * reflection) mapping src onto dst — same closed form as the iOS port.
     */
    private fun similarityTransform(src: List<PointF>, dst: List<PointF>): Matrix? {
        if (src.size < 2 || src.size != dst.size) return null
        val n = src.size.toFloat()
        var mx = 0f; var my = 0f; var mu = 0f; var mv = 0f
        for (i in src.indices) { mx += src[i].x; my += src[i].y; mu += dst[i].x; mv += dst[i].y }
        mx /= n; my /= n; mu /= n; mv /= n
        var d = 0f; var ac = 0f; var bc = 0f
        for (i in src.indices) {
            val x = src[i].x - mx; val y = src[i].y - my
            val u = dst[i].x - mu; val v = dst[i].y - mv
            d += x * x + y * y
            ac += x * u + y * v
            bc += x * v - y * u
        }
        if (d <= 1e-6f) return null
        val a = ac / d; val b = bc / d
        val tx = mu - a * mx + b * my
        val ty = mv - b * mx - a * my
        // x' = a*x - b*y + tx ; y' = b*x + a*y + ty
        return Matrix().apply {
            setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
