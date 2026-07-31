package com.xopp.android.render

import android.graphics.Canvas
import android.graphics.Paint
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool

/**
 * Paints a stroke's pressure-varying polyline onto a canvas at a given scale and offset. Shared by
 * the on-screen [DrawingSurfaceView] and [PdfExporter] so a stroke looks identical live and when
 * flattened. Highlighter strokes are forced translucent even when the stored colour is opaque.
 */
class StrokePainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(
        canvas: Canvas,
        pts: List<StrokePoint>,
        tool: Tool,
        color: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        if (pts.size < 2) return
        paint.color = renderColor(tool, color)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = ((a.width + b.width) / 2.0).toFloat() * scale
            canvas.drawLine(
                offsetX + (a.x * scale).toFloat(), offsetY + (a.y * scale).toFloat(),
                offsetX + (b.x * scale).toFloat(), offsetY + (b.y * scale).toFloat(),
                paint,
            )
        }
    }

    companion object {
        /** Highlighter always paints translucent even if the stored colour is opaque. */
        fun renderColor(tool: Tool, color: Int): Int =
            if (tool == Tool.HIGHLIGHTER && (color ushr 24) == 0xFF) {
                (color and 0x00FFFFFF) or 0x80000000.toInt()
            } else {
                color
            }
    }
}
