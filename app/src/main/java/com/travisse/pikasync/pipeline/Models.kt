package com.travisse.pikasync.pipeline

import android.graphics.Bitmap
import android.net.Uri

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val takenAtMs: Long,          // DATE_TAKEN if present, else DATE_ADDED * 1000
    val bucket: String,
    val relativePath: String,
    var faceCount: Int = 0,
    var faceQuality: Double = 0.0, // 0-1 from ML Kit face count + eyes-open/smiling probs
    var sharpness: Double = 0.0,   // Laplacian variance on downscaled grayscale
    var aHash: Long = 0L,          // 8x8 average hash for burst dedup
    var score: Double = 0.0,       // composite, set at rank stage
)

data class StageTiming(val name: String, val ms: Long, val detail: String)

data class Selection(val index: Int, val page: Int, val caption: String)

data class JudgeResult(
    val title: String,
    val coverIndex: Int,
    val selections: List<Selection>,
    val inputTokens: Int,
    val outputTokens: Int,
) {
    /** $3/M input, $15/M output (claude-sonnet-5). */
    val costUsd: Double get() = inputTokens * 3.0 / 1_000_000 + outputTokens * 15.0 / 1_000_000
}

data class PipelineResult(
    val timings: List<StageTiming>,
    val shortlist: List<PhotoItem>,       // the 48 sent to the judge, index = position
    val contactSheets: List<Bitmap>,
    val judge: JudgeResult?,
    val error: String?,
)
