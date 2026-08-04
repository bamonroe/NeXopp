package com.xopp.android.render

import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import kotlin.math.sqrt

/**
 * Pure geometry for the **partial** ("standard") eraser: instead of deleting a whole stroke like
 * [StrokeHitTester], it rubs out only the vertices the eraser disc touches and splits the stroke
 * into the surviving contiguous pieces. Each piece keeps the original stroke's tool/colour/style/
 * fill, so the result round-trips as ordinary `<stroke>`s. Android-free and unit-tested
 * ([StrokeEraserTest]).
 */
object StrokeEraser {

    /**
     * Erase the part of [stroke] within the disc of [radius] centred at ([cx], [cy]).
     *
     * Returns `null` when the disc touches nothing (the caller keeps the stroke unchanged), or the
     * list of surviving pieces otherwise — empty if the whole stroke was rubbed out.
     *
     * The test is on the stroke's **segments**, not just its vertices, and the cut lands where the
     * segment crosses the disc boundary — so a sparse shape stroke (a two-point line, a five-point
     * rectangle) erases mid-shaft and splits in two exactly like densely-sampled freehand ink.
     * Runs of two or more surviving vertices each become a piece (a lone leftover vertex can't be
     * drawn, so it drops).
     */
    fun erase(stroke: Stroke, cx: Double, cy: Double, radius: Double): List<Stroke>? {
        val pts = stroke.points
        if (pts.isEmpty()) return null
        if (pts.size == 1) {
            val p = pts[0]
            return if (dist(cx, cy, p.x, p.y) <= radius + p.width / 2.0) emptyList() else null
        }

        val pieces = mutableListOf<Stroke>()
        var run = mutableListOf<StrokePoint>()
        var touched = false
        fun flush() {
            if (run.size >= 2) pieces += stroke.copy(points = run)
            run = mutableListOf()
        }

        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val reach = radius + maxOf(a.width, b.width) / 2.0
            val iv = discInterval(a, b, cx, cy, reach)
            if (iv == null) {
                if (run.isEmpty()) run += a
                run += b
                continue
            }
            touched = true
            val (t0, t1) = iv
            if (t0 > 0.0) {
                if (run.isEmpty()) run += a
                run += lerp(a, b, t0)
            } else if (run.isNotEmpty()) {
                run.removeAt(run.size - 1) // `a` itself is inside the disc
            }
            flush()
            if (t1 < 1.0) {
                run += lerp(a, b, t1)
                run += b
            }
        }
        flush()
        return if (touched) pieces else null
    }

    /**
     * The sub-range of the segment `a`→`b` that lies inside the disc of [reach] centred at
     * ([cx], [cy]), as a clipped `t` interval in `[0, 1]`, or `null` when the segment misses it.
     */
    private fun discInterval(
        a: StrokePoint,
        b: StrokePoint,
        cx: Double,
        cy: Double,
        reach: Double,
    ): Pair<Double, Double>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val fx = a.x - cx
        val fy = a.y - cy
        val aa = dx * dx + dy * dy
        if (aa <= 1e-12) return if (dist(cx, cy, a.x, a.y) <= reach) 0.0 to 1.0 else null
        val bb = 2.0 * (fx * dx + fy * dy)
        val cc = fx * fx + fy * fy - reach * reach
        val disc = bb * bb - 4.0 * aa * cc
        if (disc < 0.0) return null
        val root = sqrt(disc)
        val t0 = ((-bb - root) / (2.0 * aa)).coerceIn(0.0, 1.0)
        val t1 = ((-bb + root) / (2.0 * aa)).coerceIn(0.0, 1.0)
        return if (t0 >= 1.0 && t1 >= 1.0 || t0 <= 0.0 && t1 <= 0.0) {
            // The whole segment sits on one side of the disc — unless it genuinely grazes an end.
            if (cc <= 0.0 || dist(cx, cy, b.x, b.y) <= reach) t0 to t1 else null
        } else {
            t0 to t1
        }
    }

    private fun lerp(a: StrokePoint, b: StrokePoint, t: Double): StrokePoint =
        StrokePoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            width = a.width + (b.width - a.width) * t,
        )

    private fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
