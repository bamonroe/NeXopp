package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A geometric shape the shape tools draw. Each is emitted as an ordinary constant-width stroke. */
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE }

/**
 * Pure geometry that turns a drag (start → end, in page-local pt) into the vertex list of a shape.
 * Shapes round-trip as normal `<stroke>` point-lists — desktop Xournal++ records line/arrow/rect/
 * ellipse the same way — so nothing new touches the `.xopp` format. Kept Android-free so it's
 * unit-testable on the JVM ([ShapeBuilderTest]). All points carry the same constant [widthPt].
 */
object ShapeBuilder {

    /** Length of an arrowhead barb relative to the shaft, and its opening half-angle. */
    private const val ARROW_HEAD_FRACTION = 0.18
    private const val ARROW_HEAD_MIN_PT = 8.0
    private const val ARROW_HEAD_MAX_PT = 28.0
    private val ARROW_HALF_ANGLE = Math.toRadians(28.0)

    /** How many segments approximate an ellipse (more when it's larger, clamped for sanity). */
    private const val ELLIPSE_MIN_SEGMENTS = 24
    private const val ELLIPSE_MAX_SEGMENTS = 96

    fun build(
        kind: ShapeKind,
        startX: Double, startY: Double,
        endX: Double, endY: Double,
        widthPt: Double,
    ): List<StrokePoint> = when (kind) {
        ShapeKind.LINE -> line(startX, startY, endX, endY, widthPt)
        ShapeKind.ARROW -> arrow(startX, startY, endX, endY, widthPt)
        ShapeKind.RECTANGLE -> rectangle(startX, startY, endX, endY, widthPt)
        ShapeKind.ELLIPSE -> ellipse(startX, startY, endX, endY, widthPt)
    }

    private fun p(x: Double, y: Double, w: Double) = StrokePoint(x, y, w)

    private fun line(sx: Double, sy: Double, ex: Double, ey: Double, w: Double) =
        listOf(p(sx, sy, w), p(ex, ey, w))

    /** A shaft plus a V-shaped head, traced as one polyline (barb, tip, barb) so it's one stroke. */
    private fun arrow(sx: Double, sy: Double, ex: Double, ey: Double, w: Double): List<StrokePoint> {
        val len = hypot(ex - sx, ey - sy)
        if (len == 0.0) return line(sx, sy, ex, ey, w)
        val head = (len * ARROW_HEAD_FRACTION).coerceIn(ARROW_HEAD_MIN_PT, ARROW_HEAD_MAX_PT)
        val dir = atan2(ey - sy, ex - sx)
        val left = dir + PI - ARROW_HALF_ANGLE
        val right = dir + PI + ARROW_HALF_ANGLE
        val lx = ex + head * cos(left)
        val ly = ey + head * sin(left)
        val rx = ex + head * cos(right)
        val ry = ey + head * sin(right)
        return listOf(p(sx, sy, w), p(ex, ey, w), p(lx, ly, w), p(ex, ey, w), p(rx, ry, w))
    }

    /** A closed rectangle from the drag's bounding box (corners in order, back to the start). */
    private fun rectangle(sx: Double, sy: Double, ex: Double, ey: Double, w: Double): List<StrokePoint> {
        val l = minOf(sx, ex); val r = maxOf(sx, ex)
        val top = minOf(sy, ey); val bot = maxOf(sy, ey)
        return listOf(
            p(l, top, w), p(r, top, w), p(r, bot, w), p(l, bot, w), p(l, top, w),
        )
    }

    /** An axis-aligned ellipse inscribed in the drag's bounding box, sampled to a closed polyline. */
    private fun ellipse(sx: Double, sy: Double, ex: Double, ey: Double, w: Double): List<StrokePoint> {
        val cx = (sx + ex) / 2.0
        val cy = (sy + ey) / 2.0
        val rx = kotlin.math.abs(ex - sx) / 2.0
        val ry = kotlin.math.abs(ey - sy) / 2.0
        val perimeter = 2.0 * PI * maxOf(rx, ry)
        val segments = (perimeter / 8.0).toInt().coerceIn(ELLIPSE_MIN_SEGMENTS, ELLIPSE_MAX_SEGMENTS)
        val out = ArrayList<StrokePoint>(segments + 1)
        for (i in 0..segments) {
            val a = 2.0 * PI * i / segments
            out += p(cx + rx * cos(a), cy + ry * sin(a), w)
        }
        return out
    }
}
