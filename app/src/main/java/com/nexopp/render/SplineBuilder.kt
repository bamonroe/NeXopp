package com.nexopp.render

import com.nexopp.format.model.StrokePoint
import kotlin.math.hypot

/**
 * One control point of a spline: an anchor the curve passes through, plus the tangent handle that
 * shapes the curve on either side of it. A zero tangent gives a corner (the segments meet straight),
 * which is what a plain tap lays down; dragging away from the tap grows the handle.
 *
 * The handle is stored as a vector from the anchor: the outgoing Bézier control point is
 * `anchor + tangent` and the incoming one `anchor - tangent`, so the curve is smooth (C¹) at
 * every node by construction — the same feel as desktop Xournal++'s spline tool.
 */
data class SplineNode(
    val x: Double,
    val y: Double,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
)

/**
 * Pure geometry that flattens a chain of [SplineNode]s into the vertex list of one stroke, by
 * sampling a cubic Bézier per node pair. Splines have no representation of their own in `.xopp` —
 * desktop Xournal++ also commits them as an ordinary `<stroke>` point list — so nothing here
 * touches the file format. Android-free, hence unit-testable on the JVM ([SplineBuilderTest]).
 */
object SplineBuilder {

    /** Roughly one sample per this many pt of control polygon, clamped so tiny/huge curves stay sane. */
    private const val PT_PER_SEGMENT = 6.0
    private const val MIN_SEGMENTS = 8
    private const val MAX_SEGMENTS = 48

    /** All points carry the same constant [widthPt] — a spline is constant-width like the other shapes. */
    fun build(nodes: List<SplineNode>, widthPt: Double): List<StrokePoint> {
        if (nodes.size < 2) return nodes.map { StrokePoint(it.x, it.y, widthPt) }
        val out = ArrayList<StrokePoint>()
        out += StrokePoint(nodes[0].x, nodes[0].y, widthPt)
        for (i in 0 until nodes.size - 1) {
            appendSegment(nodes[i], nodes[i + 1], widthPt, out)
        }
        return out
    }

    /** Sample the cubic from [a] to [b], skipping t=0 because the previous segment already emitted it. */
    private fun appendSegment(a: SplineNode, b: SplineNode, w: Double, out: MutableList<StrokePoint>) {
        val c1x = a.x + a.tx
        val c1y = a.y + a.ty
        val c2x = b.x - b.tx
        val c2y = b.y - b.ty
        val steps = segmentsFor(a.x, a.y, c1x, c1y, c2x, c2y, b.x, b.y)
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            out += StrokePoint(
                cubic(t, a.x, c1x, c2x, b.x),
                cubic(t, a.y, c1y, c2y, b.y),
                w,
            )
        }
    }

    /** How finely to sample one cubic: proportional to the length of its control polygon. */
    private fun segmentsFor(
        x0: Double, y0: Double, x1: Double, y1: Double,
        x2: Double, y2: Double, x3: Double, y3: Double,
    ): Int {
        val len = hypot(x1 - x0, y1 - y0) + hypot(x2 - x1, y2 - y1) + hypot(x3 - x2, y3 - y2)
        return (len / PT_PER_SEGMENT).toInt().coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
    }

    /** De Casteljau-free cubic Bézier evaluation on one axis. */
    private fun cubic(t: Double, p0: Double, p1: Double, p2: Double, p3: Double): Double {
        val u = 1.0 - t
        return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3
    }
}
