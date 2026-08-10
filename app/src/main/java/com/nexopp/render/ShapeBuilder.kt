package com.nexopp.render

import com.nexopp.format.model.StrokePoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A geometric shape the shape tools draw. Each is emitted as an ordinary constant-width stroke. */
enum class ShapeKind { LINE, ARROW, DOUBLE_ARROW, COORDINATE_AXIS, RECTANGLE, ELLIPSE, SPLINE }

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
        ShapeKind.DOUBLE_ARROW -> doubleArrow(startX, startY, endX, endY, widthPt)
        ShapeKind.COORDINATE_AXIS -> coordinateAxis(startX, startY, endX, endY, widthPt)
        ShapeKind.RECTANGLE -> rectangle(startX, startY, endX, endY, widthPt)
        ShapeKind.ELLIPSE -> ellipse(startX, startY, endX, endY, widthPt)
        // A spline is laid down over many taps, so its real geometry comes from [SplineBuilder]; the
        // two-point case this signature can express is just a straight line between the ends.
        ShapeKind.SPLINE -> line(startX, startY, endX, endY, widthPt)
    }

    private fun p(x: Double, y: Double, w: Double) = StrokePoint(x, y, w)

    private fun line(sx: Double, sy: Double, ex: Double, ey: Double, w: Double) =
        listOf(p(sx, sy, w), p(ex, ey, w))

    /** A shaft plus a V-shaped head, traced as one polyline (barb, tip, barb) so it's one stroke. */
    private fun arrow(sx: Double, sy: Double, ex: Double, ey: Double, w: Double): List<StrokePoint> {
        val len = hypot(ex - sx, ey - sy)
        if (len == 0.0) return line(sx, sy, ex, ey, w)
        return listOf(p(sx, sy, w)) + head(sx, sy, ex, ey, len, w)
    }

    /**
     * A shaft with a head at *both* ends. Traced as one polyline: the tail head is drawn first
     * (walking back out to the tail tip), then the shaft, then the tip head — so it stays one stroke.
     */
    private fun doubleArrow(sx: Double, sy: Double, ex: Double, ey: Double, w: Double): List<StrokePoint> {
        val len = hypot(ex - sx, ey - sy)
        if (len == 0.0) return line(sx, sy, ex, ey, w)
        return head(ex, ey, sx, sy, len, w).reversed() + head(sx, sy, ex, ey, len, w)
    }

    /**
     * The V-shaped head for a shaft pointing from (sx,sy) to the tip (ex,ey): tip, barb, tip, barb.
     * The caller prefixes/joins these so the whole arrow stays a single polyline.
     */
    private fun head(
        sx: Double, sy: Double, ex: Double, ey: Double, len: Double, w: Double,
    ): List<StrokePoint> {
        val size = (len * ARROW_HEAD_FRACTION).coerceIn(ARROW_HEAD_MIN_PT, ARROW_HEAD_MAX_PT)
        val dir = atan2(ey - sy, ex - sx)
        val left = dir + PI - ARROW_HALF_ANGLE
        val right = dir + PI + ARROW_HALF_ANGLE
        return listOf(
            p(ex, ey, w),
            p(ex + size * cos(left), ey + size * sin(left), w),
            p(ex, ey, w),
            p(ex + size * cos(right), ey + size * sin(right), w),
        )
    }

    /**
     * A pair of arrowed axes meeting at the drag's start: y runs vertically to the drag's end
     * height, x runs horizontally to its end width. Traced as one polyline — y tip (with head)
     * down to the origin, then out to the x tip (with head) — so it round-trips as a single stroke.
     */
    private fun coordinateAxis(
        sx: Double, sy: Double, ex: Double, ey: Double, w: Double,
    ): List<StrokePoint> {
        val yLen = kotlin.math.abs(ey - sy)
        val xLen = kotlin.math.abs(ex - sx)
        if (yLen == 0.0 && xLen == 0.0) return line(sx, sy, ex, ey, w)
        // Axes point away from the origin in whichever direction the drag went.
        val yTip = sy + if (ey <= sy) -yLen else yLen
        val xTip = sx + if (ex >= sx) xLen else -xLen
        val yHead = if (yLen == 0.0) emptyList() else head(sx, sy, sx, yTip, yLen, w)
        val xHead = if (xLen == 0.0) emptyList() else head(sx, sy, xTip, sy, xLen, w)
        return yHead.reversed() + p(sx, sy, w) + xHead
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
