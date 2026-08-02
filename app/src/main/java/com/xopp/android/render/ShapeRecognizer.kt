package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Xournal++-style stroke shape recognition: look at a just-finished freehand stroke and, when it is
 * clearly *meant* to be a primitive, replace it with clean geometry.
 *
 * The recogniser is deliberately conservative — an unrecognised scribble must come through
 * untouched, because a wrong snap destroys handwriting. Every test below has a slack budget scaled
 * to the stroke's own size, so the same wobble tolerance applies to a thumbnail-sized circle and a
 * page-sized one.
 *
 * Output is always an ordinary constant-width point list (built via [ShapeBuilder] where a shape
 * tool already knows the geometry), so nothing new touches the `.xopp` format.
 *
 * Android-free and pure, so it unit-tests on the JVM ([ShapeRecognizerTest]).
 */
object ShapeRecognizer {

    /** Strokes whose bounding box is smaller than this (pt) are ticks and dots — never snapped. */
    private const val MIN_SIZE_PT = 14.0

    /** Corner-finding budget, as a fraction of the stroke's bounding-box diagonal. */
    private const val SIMPLIFY_FRACTION = 0.055

    /**
     * Coarser budget used to count a *closed* shape's corners. A hand-drawn corner is rounded, and
     * at the finer budget above that round-off survives as two vertices — enough to make a rectangle
     * look like a pentagon. Only the corner count needs this slack; the geometry we emit comes from
     * the bounding box or the corners themselves.
     */
    private const val CORNER_FRACTION = 0.11

    /** A stroke is "closed" when its end lands this close to its start (fraction of the diagonal). */
    private const val CLOSE_FRACTION = 0.28

    /** A stroke is a straight line when it never strays this far from its chord (fraction of it). */
    private const val LINE_STRAIGHTNESS = 0.06

    /** Ellipse fit: allowed spread of the normalised radius (1.0 = perfectly on the ellipse). */
    private const val ELLIPSE_RADIUS_SPREAD = 0.13

    /** How square a corner must be to count as a right angle, in degrees. */
    private const val RIGHT_ANGLE_SLACK_DEG = 22.0

    /** How close a rectangle's edges must run to the page axes before we snap it to the bbox. */
    private const val AXIS_SLACK_DEG = 14.0

    /** Longest open polyline we'll accept; beyond this it's handwriting, not a drawn shape. */
    private const val MAX_POLYLINE_VERTICES = 6

    /**
     * The cleaned-up replacement for [points], or **null** when the stroke doesn't clearly resemble
     * any primitive (the caller then keeps the freehand stroke as drawn). [widthPt] is the constant
     * width every emitted vertex carries.
     */
    fun recognize(points: List<StrokePoint>, widthPt: Double): List<StrokePoint>? {
        if (points.size < 3) return null
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < MIN_SIZE_PT) return null

        val poly = StrokeSimplifier.simplify(points, diag * SIMPLIFY_FRACTION)
        val first = points.first()
        val last = points.last()
        val closed = hypot(last.x - first.x, last.y - first.y) <= CLOSE_FRACTION * diag

        return if (closed) {
            val coarse = StrokeSimplifier.simplify(points, diag * CORNER_FRACTION)
            closedShape(points, coarse, minX, minY, maxX, maxY, widthPt)
        } else {
            openShape(points, poly, widthPt)
        }
    }

    /** Line, arrow, or plain polyline — the open-stroke family, tried most-specific first. */
    private fun openShape(points: List<StrokePoint>, poly: List<StrokePoint>, w: Double): List<StrokePoint>? {
        val a = points.first()
        val b = points.last()
        val chord = hypot(b.x - a.x, b.y - a.y)
        if (chord > 0 && maxDeviation(points, a, b) <= LINE_STRAIGHTNESS * chord) {
            return ShapeBuilder.build(ShapeKind.LINE, a.x, a.y, b.x, b.y, w)
        }
        arrow(poly, w)?.let { return it }
        val corners = poly.size
        if (corners in 3..MAX_POLYLINE_VERTICES) return poly.map { StrokePoint(it.x, it.y, w) }
        return null
    }

    /**
     * An arrow drawn in one stroke: a long shaft, then one or two short barbs folded back over it.
     * Recognised only when every barb is short relative to the shaft *and* turns back sharply, so a
     * lazy two-segment zig-zag doesn't become an arrowhead.
     */
    private fun arrow(poly: List<StrokePoint>, w: Double): List<StrokePoint>? {
        if (poly.size !in 3..4) return null
        val shaftStart = poly[0]
        val tip = poly[1]
        val shaft = hypot(tip.x - shaftStart.x, tip.y - shaftStart.y)
        if (shaft <= 0.0) return null
        for (i in 2 until poly.size) {
            val barbFrom = poly[i - 1]
            val barb = poly[i]
            val len = hypot(barb.x - barbFrom.x, barb.y - barbFrom.y)
            if (len > 0.45 * shaft) return null
            // Measured at the tip, the barb must fold back toward the shaft's start.
            if (angleAt(tip, shaftStart, barb) > 75.0) return null
        }
        return ShapeBuilder.build(ShapeKind.ARROW, shaftStart.x, shaftStart.y, tip.x, tip.y, w)
    }

    /** Ellipse, rectangle, or triangle — the closed-stroke family. */
    private fun closedShape(
        points: List<StrokePoint>,
        poly: List<StrokePoint>,
        minX: Double, minY: Double, maxX: Double, maxY: Double,
        w: Double,
    ): List<StrokePoint>? {
        if (isEllipse(points, minX, minY, maxX, maxY)) {
            return ShapeBuilder.build(ShapeKind.ELLIPSE, minX, minY, maxX, maxY, w)
        }
        val corners = cornersOf(poly)
        return when {
            corners.size == 4 && isRectangular(corners) ->
                if (isAxisAligned(corners)) ShapeBuilder.build(ShapeKind.RECTANGLE, minX, minY, maxX, maxY, w)
                else closedPolygon(corners, w)
            corners.size == 3 -> closedPolygon(corners, w)
            else -> null
        }
    }

    /**
     * True when every sample sits at roughly the same normalised radius on the bbox-inscribed
     * ellipse. Normalising by the box's own half-axes means an oval passes as readily as a circle.
     */
    private fun isEllipse(
        points: List<StrokePoint>,
        minX: Double, minY: Double, maxX: Double, maxY: Double,
    ): Boolean {
        val rx = (maxX - minX) / 2.0
        val ry = (maxY - minY) / 2.0
        if (rx < 1e-6 || ry < 1e-6) return false
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        var sum = 0.0
        var sumSq = 0.0
        for (p in points) {
            val dx = (p.x - cx) / rx
            val dy = (p.y - cy) / ry
            val r = sqrt(dx * dx + dy * dy)
            sum += r
            sumSq += r * r
        }
        val n = points.size
        val mean = sum / n
        val spread = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        return spread <= ELLIPSE_RADIUS_SPREAD && abs(mean - 1.0) <= 0.18
    }

    /** The polygon's distinct corners: the simplified vertices with the closing duplicate dropped. */
    private fun cornersOf(poly: List<StrokePoint>): List<StrokePoint> {
        if (poly.size < 4) return poly
        val body = poly.dropLast(1)
        // The closing vertex is a near-duplicate of the first; the drop above already removed it, but
        // a stroke that overshoots leaves a stub segment, so collapse that too.
        val a = body.first()
        val b = body.last()
        val span = hypot(poly.maxOf { it.x } - poly.minOf { it.x }, poly.maxOf { it.y } - poly.minOf { it.y })
        return if (body.size > 3 && hypot(b.x - a.x, b.y - a.y) < 0.15 * span) body.dropLast(1) else body
    }

    /** True when all four interior angles are within [RIGHT_ANGLE_SLACK_DEG] of square. */
    private fun isRectangular(c: List<StrokePoint>): Boolean = c.indices.all { i ->
        val prev = c[(i + c.size - 1) % c.size]
        val next = c[(i + 1) % c.size]
        abs(angleAt(c[i], prev, next) - 90.0) <= RIGHT_ANGLE_SLACK_DEG
    }

    /** True when every edge runs within [AXIS_SLACK_DEG] of horizontal or vertical. */
    private fun isAxisAligned(c: List<StrokePoint>): Boolean = c.indices.all { i ->
        val next = c[(i + 1) % c.size]
        val dx = abs(next.x - c[i].x)
        val dy = abs(next.y - c[i].y)
        val off = Math.toDegrees(kotlin.math.atan2(minOf(dx, dy), maxOf(dx, dy)))
        off <= AXIS_SLACK_DEG
    }

    /** [corners] as a closed constant-width polyline (last vertex repeats the first). */
    private fun closedPolygon(corners: List<StrokePoint>, w: Double): List<StrokePoint> =
        corners.map { StrokePoint(it.x, it.y, w) } + StrokePoint(corners[0].x, corners[0].y, w)

    /** The interior angle at [v] between the rays to [a] and [b], in degrees (0 when degenerate). */
    private fun angleAt(v: StrokePoint, a: StrokePoint, b: StrokePoint): Double {
        val ax = a.x - v.x; val ay = a.y - v.y
        val bx = b.x - v.x; val by = b.y - v.y
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-9 || lb < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    /** The farthest any sample strays from the chord [a]–[b]. */
    private fun maxDeviation(points: List<StrokePoint>, a: StrokePoint, b: StrokePoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-9) return 0.0
        return points.maxOf { abs(dy * (it.x - a.x) - dx * (it.y - a.y)) / len }
    }
}
