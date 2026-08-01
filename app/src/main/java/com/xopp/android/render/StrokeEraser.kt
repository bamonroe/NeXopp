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
     * list of surviving pieces otherwise — empty if the whole stroke was rubbed out. A vertex is
     * erased when the disc reaches within the vertex's own half-width of it; runs of two or more
     * surviving vertices each become a piece (a lone leftover vertex can't be drawn, so it drops).
     */
    fun erase(stroke: Stroke, cx: Double, cy: Double, radius: Double): List<Stroke>? {
        val pts = stroke.points
        if (pts.isEmpty()) return null
        val hit = BooleanArray(pts.size) { i ->
            dist(cx, cy, pts[i].x, pts[i].y) <= radius + pts[i].width / 2.0
        }
        if (hit.none { it }) return null

        val pieces = mutableListOf<Stroke>()
        var run = mutableListOf<StrokePoint>()
        fun flush() {
            if (run.size >= 2) pieces += stroke.copy(points = run)
            run = mutableListOf()
        }
        for (i in pts.indices) {
            if (hit[i]) flush() else run += pts[i]
        }
        flush()
        return pieces
    }

    private fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
