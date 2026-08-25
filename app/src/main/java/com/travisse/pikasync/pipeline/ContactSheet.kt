package com.travisse.pikasync.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stage 6: labeled 4x4 contact sheets on a white background, 420px cells. */
object ContactSheet {
    private const val CELL = 420
    private const val LABEL_H = 56
    private const val COLS = 4
    private const val ROWS = 4

    fun render(context: Context, shortlist: List<PhotoItem>): List<Bitmap> {
        val fmt = SimpleDateFormat("M/d", Locale.US)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        val sheets = mutableListOf<Bitmap>()
        val perSheet = COLS * ROWS
        var index = 0
        for (chunk in shortlist.chunked(perSheet)) {
            val sheet = Bitmap.createBitmap(COLS * CELL, ROWS * (CELL + LABEL_H), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            chunk.forEachIndexed { i, photo ->
                val col = i % COLS
                val row = i / COLS
                val x = col * CELL
                val y = row * (CELL + LABEL_H)
                val thumb = try {
                    context.contentResolver.loadThumbnail(photo.uri, Size(CELL, CELL), null)
                } catch (_: Exception) { null }
                if (thumb != null) {
                    // center-crop into the square cell
                    val side = minOf(thumb.width, thumb.height)
                    val src = Rect(
                        (thumb.width - side) / 2, (thumb.height - side) / 2,
                        (thumb.width + side) / 2, (thumb.height + side) / 2,
                    )
                    canvas.drawBitmap(thumb, src, Rect(x, y, x + CELL, y + CELL), null)
                    thumb.recycle()
                }
                // Known names on the label steer the judge's starred-name
                // balance rule (mirror of iOS: skip auto "Person N" names).
                val names = photo.personIds
                    .mapNotNull { PeopleStore.nameOf(context, it) }
                    .filter { !it.startsWith("Person ") }
                    .take(2).joinToString(",")
                var label = "[$index] ${fmt.format(Date(photo.takenAtMs))} faces:${photo.faceCount}"
                if (names.isNotEmpty()) {
                    label += " $names"
                    android.util.Log.d("SheetLabel", label)
                }
                canvas.drawText(label, x + CELL / 2f, y + CELL + LABEL_H * 0.65f, textPaint)
                index++
            }
            sheets.add(sheet)
        }
        return sheets
    }
}
