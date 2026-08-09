package com.xopp.android.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.xopp.android.format.XoppColor
import com.xopp.android.format.XoppColor.withAlpha
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool

/**
 * Paints a stroke's pressure-varying polyline onto a canvas at a given scale and offset. Shared by
 * the on-screen [DrawingSurfaceView] and [PdfExporter] so a stroke looks identical live and when
 * flattened. Highlighter strokes render distinctly from the pen: a broad, constant-width band drawn
 * as one translucent path (forced translucent even when the stored colour is opaque), whereas the
 * pen tapers per-segment with pressure. A [LineStyle] other than plain paints the outline as a
 * single dashed/dotted path (constant width), and a non-null fill floods the closed outline first.
 */
class StrokePainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Fills the interior of a closed stroke (shapes / highlighter fill). */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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
        lineStyle: LineStyle = LineStyle.PLAIN,
        fill: Int? = null,
    ) {
        if (pts.size < 2) return
        if (fill != null) fillOutline(canvas, pts, color, fill, scale, offsetX, offsetY)
        paint.color = renderColor(tool, color)
        when (val mode = RenderMode.of(tool, lineStyle)) {
            RenderMode.Styled -> drawStyledLine(canvas, pts, lineStyle, scale, offsetX, offsetY)
            RenderMode.Band -> drawBand(canvas, pts, scale, offsetX, offsetY)
            RenderMode.Pressure -> drawPressureLine(canvas, pts, scale, offsetX, offsetY)
        }
    }

    /** Flood the closed polyline with the stroke colour at the fill alpha (drawn under the outline). */
    private fun fillOutline(
        canvas: Canvas, pts: List<StrokePoint>, color: Int, fill: Int,
        scale: Float, offsetX: Float, offsetY: Float,
    ) {
        fillPaint.color = color.withAlpha(fill)
        path.rewind()
        buildPath(pts, scale, offsetX, offsetY, close = true)
        canvas.drawPath(path, fillPaint)
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
     * A dashed/dotted stroke: one constant-width [Path] with a [DashPathEffect]. Drawn in a single
     * pass (not per-segment) so the dash phase runs continuously along the whole polyline instead of
     * restarting at every vertex. Width is constant (the mean vertex width) — desktop dashed strokes
     * are uniform-width.
     */
    private fun drawStyledLine(
        canvas: Canvas, pts: List<StrokePoint>, lineStyle: LineStyle,
        scale: Float, offsetX: Float, offsetY: Float,
    ) {
        val w = bandWidth(pts)
        paint.strokeWidth = w.toFloat() * scale
        paint.pathEffect = dashEffect(lineStyle, w, scale)
        path.rewind()
        buildPath(pts, scale, offsetX, offsetY, close = false)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
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
        buildPath(pts, scale, offsetX, offsetY, close = false)
        canvas.drawPath(path, paint)
    }

    /** Build a polyline Path from [pts], scaled and offset. Closes the path if [close] is true. */
    private fun buildPath(pts: List<StrokePoint>, scale: Float, offsetX: Float, offsetY: Float, close: Boolean) {
        path.moveTo(offsetX + (pts[0].x * scale).toFloat(), offsetY + (pts[0].y * scale).toFloat())
        for (i in 1 until pts.size) {
            path.lineTo(offsetX + (pts[i].x * scale).toFloat(), offsetY + (pts[i].y * scale).toFloat())
        }
        if (close) path.close()
    }

    /** The render mode a stroke uses — drives the same decision tree in screen and PDF painters. */
    sealed class RenderMode {
        data object Styled : RenderMode()
        data object Band : RenderMode()
        data object Pressure : RenderMode()

        companion object {
            fun of(tool: Tool, lineStyle: LineStyle): RenderMode = when {
                lineStyle != LineStyle.PLAIN -> Styled
                tool == Tool.HIGHLIGHTER -> Band
                else -> Pressure
            }
            fun of(stroke: Stroke): RenderMode = of(stroke.tool, stroke.lineStyle)
        }
    }

    companion object {
        /** A highlighter is uniform-width; use the mean vertex width so odd inputs still render sanely. */
        fun bandWidth(pts: List<StrokePoint>): Double =
            if (pts.isEmpty()) 0.0 else pts.sumOf { it.width } / pts.size

        /** Highlighter always paints translucent even if the stored colour is opaque. */
        fun renderColor(tool: Tool, color: Int): Int =
            if (tool == Tool.HIGHLIGHTER && (color ushr 24) == 0xFF) {
                color.withAlpha(XoppColor.HIGHLIGHTER_ALPHA)
            } else {
                color
            }

        /**
         * The on/off dash pattern for a [style] in pt, scaled by the stroke [widthPt] so a thick
         * dashed line has proportionally longer dashes. Dots are a near-zero "on" run rendered as
         * a blob by the round cap. Null for [LineStyle.PLAIN]. Pure — reused by the PDF exporter.
         */
        fun dashIntervalsPt(style: LineStyle, widthPt: Double): FloatArray? {
            val u = maxOf(widthPt, 0.5).toFloat()
            return when (style) {
                LineStyle.PLAIN -> null
                LineStyle.DASHED -> floatArrayOf(4f * u, 3f * u)
                LineStyle.DASH_DOT -> floatArrayOf(4f * u, 3f * u, 0.01f * u, 3f * u)
                LineStyle.DOTTED -> floatArrayOf(0.01f * u, 2.5f * u)
            }
        }

        /** The [dashIntervalsPt] pattern scaled to screen px by [scale], as an Android effect. */
        fun dashEffect(style: LineStyle, widthPt: Double, scale: Float): DashPathEffect? {
            val pt = dashIntervalsPt(style, widthPt) ?: return null
            return DashPathEffect(FloatArray(pt.size) { pt[it] * scale }, 0f)
        }
    }
}
