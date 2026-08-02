package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StrokeSmootherTest {
    @Test fun `first sample passes through untouched`() {
        val s = StrokeSmoother().accept(10f, 20f, 0.5f)
        assertNotNull(s)
        assertEquals(10f, s!!.x, 1e-6f)
        assertEquals(20f, s.y, 1e-6f)
        assertEquals(0.5f, s.pressure, 1e-6f)
    }

    @Test fun `position noise is attenuated`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0.5f)
        // A single-sample spike sideways must not drag the line all the way out to it.
        val out = smoother.accept(0f, 40f, 0.5f, force = true)!!
        assertTrue("got ${out.y}", out.y in 1f..39f)
    }

    @Test fun `pressure is smoothed harder than position`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0f)
        val out = smoother.accept(100f, 0f, 1f, force = true)!!
        val positionProgress = out.x / 100f
        assertTrue("$positionProgress vs ${out.pressure}", out.pressure < positionProgress)
    }

    @Test fun `near-duplicate samples are decimated`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0.5f)
        assertNull(smoother.accept(0.2f, 0f, 0.5f))
    }

    @Test fun `forced samples are never decimated`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0.5f)
        assertNotNull(smoother.accept(0.2f, 0f, 0.5f, force = true))
    }

    @Test fun `a pressure change alone keeps the sample`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0.1f)
        assertNotNull(smoother.accept(0.2f, 0f, 1f))
    }

    @Test fun `reset starts a fresh stroke`() {
        val smoother = StrokeSmoother()
        smoother.accept(0f, 0f, 0.5f)
        smoother.reset()
        val out = smoother.accept(500f, 500f, 1f)!!
        assertEquals(500f, out.x, 1e-6f)
        assertEquals(1f, out.pressure, 1e-6f)
    }

    @Test fun `a long drag keeps tracking the pen`() {
        val smoother = StrokeSmoother()
        // Sub-threshold steps are decimated away, but they still advance the filter, so the next
        // point that is emitted sits on the pen rather than back where the decimation started.
        smoother.accept(0f, 0f, 0.5f)
        for (i in 1 until 200) smoother.accept(i.toFloat(), 0f, 0.5f)
        val last = smoother.accept(200f, 0f, 0.5f, force = true)!!
        assertTrue("lagged at ${last.x}", abs(200f - last.x) < 2f)
    }
}

class StrokeSimplifierTest {
    private fun pt(x: Double, y: Double, w: Double = 1.0) = StrokePoint(x, y, w)

    @Test fun `short strokes are returned unchanged`() {
        val pts = listOf(pt(0.0, 0.0), pt(5.0, 5.0))
        assertEquals(pts, StrokeSimplifier.simplify(pts))
    }

    @Test fun `collinear interior points are dropped`() {
        val pts = (0..10).map { pt(it.toDouble(), 0.0) }
        assertEquals(listOf(pt(0.0, 0.0), pt(10.0, 0.0)), StrokeSimplifier.simplify(pts))
    }

    @Test fun `a real corner is preserved`() {
        val pts = listOf(pt(0.0, 0.0), pt(5.0, 0.0), pt(10.0, 0.0), pt(10.0, 5.0), pt(10.0, 10.0))
        val out = StrokeSimplifier.simplify(pts)
        assertEquals(listOf(pt(0.0, 0.0), pt(10.0, 0.0), pt(10.0, 10.0)), out)
    }

    @Test fun `endpoints always survive`() {
        val pts = (0..20).map { pt(it.toDouble(), 0.0) }
        val out = StrokeSimplifier.simplify(pts)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test fun `curvature above the tolerance is kept`() {
        val pts = listOf(pt(0.0, 0.0), pt(5.0, 3.0), pt(10.0, 0.0))
        assertEquals(3, StrokeSimplifier.simplify(pts).size)
    }

    @Test fun `widths ride along with surviving points`() {
        val pts = listOf(pt(0.0, 0.0, 1.0), pt(5.0, 3.0, 2.5), pt(10.0, 0.0, 0.5))
        val out = StrokeSimplifier.simplify(pts)
        assertEquals(2.5, out[1].width, 1e-9)
    }

    @Test fun `simplification never deviates beyond the tolerance`() {
        val pts = (0..100).map { pt(it.toDouble(), if (it % 2 == 0) 0.0 else 0.1) }
        val out = StrokeSimplifier.simplify(pts, tolerance = 0.35)
        assertTrue("kept ${out.size}", out.size < pts.size)
    }

    @Test fun `tolerance shrinks as the canvas zooms in`() {
        assertEquals(StrokeSimplifier.TOLERANCE_PT, StrokeSimplifier.toleranceFor(1f), 1e-9)
        assertEquals(StrokeSimplifier.TOLERANCE_PT / 8.0, StrokeSimplifier.toleranceFor(8f), 1e-9)
        assertTrue(StrokeSimplifier.toleranceFor(8f) < StrokeSimplifier.toleranceFor(2f))
    }

    @Test fun `zooming out never widens the tolerance past twice the default`() {
        val widest = StrokeSimplifier.TOLERANCE_PT * 2
        assertEquals(widest, StrokeSimplifier.toleranceFor(0.5f), 1e-9)
        assertEquals(widest, StrokeSimplifier.toleranceFor(0.1f), 1e-9)
    }

    @Test fun `fine detail survives when drawn zoomed in`() {
        // A gentle curve whose sagitta sits just under the 100% budget — thinned flat at 100%,
        // preserved at 800% where that same error would be visible on screen.
        val pts = listOf(pt(0.0, 0.0), pt(5.0, 0.3), pt(10.0, 0.0))
        assertEquals(2, StrokeSimplifier.simplify(pts, StrokeSimplifier.toleranceFor(1f)).size)
        assertEquals(3, StrokeSimplifier.simplify(pts, StrokeSimplifier.toleranceFor(8f)).size)
    }
}
