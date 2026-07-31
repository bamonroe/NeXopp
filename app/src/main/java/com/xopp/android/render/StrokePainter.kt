package com.xopp.android.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool

/**
 * Paints a stroke's pressure-varying polyline onto a canvas at a given scale and offset. Shared by
 * the on-screen [DrawingSurfaceView] and [PdfExporter] so a stroke looks identical live and when
 * flattened. Highlighter strokes render distinctly from the pen: a broad, constant-width band drawn
 * as one translucent path (forced translucent even when the stored colour is opaque), whereas the
 * pen tapers per-segment with pressure.
 */
class StrokePainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Reused across highlighter strokes so a translucent band composites in a single pass. */
    private val path = Path()

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
        if (tool == Tool.HIGHLIGHTER) {
            drawBand(canvas, pts, scale, offsetX, offsetY)
        } else {
            drawPressureLine(canvas, pts, scale, offsetX, offsetY)
        }
    }

    /** The pen: each segment is its own line so the width can track pressure between vertices. */
    private fun drawPressureLine(
        canvas: Canvas, pts: List<StrokePoint>, scale: Float, offsetX: Float, offsetY: Float,
    ) {
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

    /**
     * The highlighter: one constant-width [Path] drawn in a single pass. Because the whole band is
     * rasterised once, its translucent alpha does not stack where the stroke overlaps itself at
     * joins (drawing segment-by-segment would bead into darker blobs at every vertex).
     */
    private fun drawBand(
        canvas: Canvas, pts: List<StrokePoint>, scale: Float, offsetX: Float, offsetY: Float,
    ) {
        paint.strokeWidth = bandWidth(pts).toFloat() * scale
        path.rewind()
        path.moveTo(offsetX + (pts[0].x * scale).toFloat(), offsetY + (pts[0].y * scale).toFloat())
        for (i in 1 until pts.size) {
            path.lineTo(offsetX + (pts[i].x * scale).toFloat(), offsetY + (pts[i].y * scale).toFloat())
        }
        canvas.drawPath(path, paint)
    }

    companion object {
        /** A highlighter is uniform-width; use the mean vertex width so odd inputs still render sanely. */
        fun bandWidth(pts: List<StrokePoint>): Double =
            if (pts.isEmpty()) 0.0 else pts.sumOf { it.width } / pts.size

        /** Highlighter always paints translucent even if the stored colour is opaque. */
        fun renderColor(tool: Tool, color: Int): Int =
            if (tool == Tool.HIGHLIGHTER && (color ushr 24) == 0xFF) {
                (color and 0x00FFFFFF) or 0x80000000.toInt()
            } else {
                color
            }
    }
}
