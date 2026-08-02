package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recogniser's contract: obvious primitives snap, and anything else is left alone. The wobble
 * helper below perturbs every sample deterministically, so these are hand-drawn strokes, not the
 * exact geometry the recogniser emits.
 */
class ShapeRecognizerTest {

    private val w = 1.5

    /** A repeatable pseudo-random jitter, so "hand-drawn" input stays the same run to run. */
    private fun wobble(i: Int, amount: Double) = amount * sin(i * 12.9898)

    private fun pts(vararg xy: Double): List<StrokePoint> =
        xy.toList().chunked(2).map { StrokePoint(it[0], it[1], w) }

    private fun sample(n: Int, jitter: Double = 0.0, f: (Double) -> Pair<Double, Double>) =
        (0..n).map { i ->
            val (x, y) = f(i.toDouble() / n)
            StrokePoint(x + wobble(i, jitter), y + wobble(i * 7, jitter), w)
        }

    @Test
    fun `a wobbly line becomes a two-point line`() {
        val out = ShapeRecognizer.recognize(sample(40, jitter = 1.0) { t -> 20.0 + 200 * t to 60.0 + 40 * t }, w)
        assertEquals(listOf(2), listOf(out?.size))
    }

    @Test
    fun `a wobbly circle becomes an ellipse`() {
        val out = ShapeRecognizer.recognize(
            sample(72, jitter = 1.5) { t -> 100 + 80 * cos(2 * PI * t) to 100 + 80 * sin(2 * PI * t) },
            w,
        )
        assertNotNull(out)
        // Closed, and every vertex sits near the fitted radius.
        val cx = out!!.sumOf { it.x } / out.size
        val cy = out.sumOf { it.y } / out.size
        assertTrue(out.all { kotlin.math.abs(hypot(it.x - cx, it.y - cy) - 80.0) < 8.0 })
        assertEquals(out.first().x, out.last().x, 1e-9)
    }

    @Test
    fun `a wobbly rectangle snaps to its bounding box`() {
        val path = ArrayList<StrokePoint>()
        fun edge(x0: Double, y0: Double, x1: Double, y1: Double) {
            for (i in 0..20) {
                val t = i / 20.0
                path += StrokePoint(x0 + (x1 - x0) * t + wobble(i, 0.8), y0 + (y1 - y0) * t + wobble(i * 3, 0.8), w)
            }
        }
        edge(10.0, 10.0, 210.0, 10.0); edge(210.0, 10.0, 210.0, 110.0)
        edge(210.0, 110.0, 10.0, 110.0); edge(10.0, 110.0, 10.0, 10.0)

        val out = ShapeRecognizer.recognize(path, w)
        assertNotNull(out)
        assertEquals(5, out!!.size)
        assertTrue(out.all { kotlin.math.abs(it.x - 10.0) < 3 || kotlin.math.abs(it.x - 210.0) < 3 })
    }

    @Test
    fun `a closed triangle keeps its three corners`() {
        val out = ShapeRecognizer.recognize(
            pts(
                0.0, 0.0, 50.0, -50.0, 100.0, -100.0, 150.0, -50.0, 200.0, 0.0,
                150.0, 0.0, 100.0, 0.0, 50.0, 0.0, 1.0, 0.5,
            ),
            w,
        )
        assertNotNull(out)
        assertEquals(4, out!!.size) // three corners plus the closing repeat
        assertEquals(out.first().x, out.last().x, 1e-9)
    }

    @Test
    fun `an arrow keeps its shaft direction and gains a head`() {
        // Shaft out to the tip, then one barb folded back over it.
        val out = ShapeRecognizer.recognize(pts(0.0, 0.0, 200.0, 0.0, 170.0, -20.0), w)
        assertNotNull(out)
        assertEquals(ShapeBuilder.build(ShapeKind.ARROW, 0.0, 0.0, 200.0, 0.0, w), out)
    }

    @Test
    fun `an open zig-zag stays a polyline, not an arrow`() {
        val out = ShapeRecognizer.recognize(pts(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 200.0, 100.0), w)
        assertEquals(4, out?.size)
    }

    @Test
    fun `handwriting is left alone`() {
        // A many-cornered squiggle matches nothing; the caller must keep the freehand stroke.
        val squiggle = sample(120) { t -> 20 + 300 * t to 60 + 25 * sin(18 * PI * t) }
        assertNull(ShapeRecognizer.recognize(squiggle, w))
    }

    @Test
    fun `a tiny tick is never snapped`() {
        assertNull(ShapeRecognizer.recognize(pts(0.0, 0.0, 3.0, 2.0, 6.0, 1.0), w))
    }

    @Test
    fun `every emitted vertex carries the requested width`() {
        val out = ShapeRecognizer.recognize(sample(30, jitter = 0.5) { t -> 0.0 + 150 * t to 0.0 }, w)
        assertTrue(out!!.all { it.width == w })
    }
}
