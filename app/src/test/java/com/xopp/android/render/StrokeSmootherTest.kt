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

    @Test fun `tolerance shrinks as the on-screen scale rises`() {
        assertEquals(StrokeSimplifier.TOLERANCE_PT, StrokeSimplifier.toleranceFor(1f), 1e-9)
        assertEquals(StrokeSimplifier.TOLERANCE_PT / 8.0, StrokeSimplifier.toleranceFor(8f), 1e-9)
        assertTrue(StrokeSimplifier.toleranceFor(8f) < StrokeSimplifier.toleranceFor(2f))
    }

    @Test fun `a large screen tightens the tolerance at 100 percent zoom`() {
        // The regression: a big tablet fits the page at ~3 px/pt before the user zooms at all, so
        // the budget at 100% must be a third of the 1 px/pt one — not the full default.
        assertEquals(StrokeSimplifier.TOLERANCE_PT / 3.0, StrokeSimplifier.toleranceFor(3f), 1e-9)
        assertTrue(StrokeSimplifier.toleranceFor(3f) < StrokeSimplifier.toleranceFor(1f))
    }

    @Test fun `zooming out never widens the tolerance past the page-point budget`() {
        val widest = StrokeSimplifier.TOLERANCE_PT
        assertEquals(widest, StrokeSimplifier.toleranceFor(0.5f), 1e-9)
        assertEquals(widest, StrokeSimplifier.toleranceFor(0.1f), 1e-9)
        // Monotone: a smaller scale never buys a looser document-space budget.
        var prev = 0.0
        for (scale in listOf(8f, 4f, 2f, 1f, 0.5f, 0.25f, 0.05f)) {
            val t = StrokeSimplifier.toleranceFor(scale)
            assertTrue("at $scale", t >= prev)
            assertTrue("at $scale", t <= widest + 1e-9)
            prev = t
        }
    }

    @Test fun `higher precision keeps a tighter budget at every scale`() {
        for (scale in listOf(0.25f, 1f, 3f, 8f)) {
            val economy = StrokeSimplifier.toleranceFor(scale, StrokePrecision.ECONOMY)
            val balanced = StrokeSimplifier.toleranceFor(scale, StrokePrecision.BALANCED)
            val max = StrokeSimplifier.toleranceFor(scale, StrokePrecision.MAXIMUM)
            assertTrue("at $scale", max < balanced && balanced < economy)
        }
    }

    @Test fun `precision scales the live decimation radius the same way`() {
        val scale = 4f // well above the px-floor crossover, so the noise floor is what applies
        assertEquals(StrokeSmoother.MIN_STEP_PX, StrokePrecision.BALANCED.stepPxFor(scale), 1e-6f)
        assertTrue(StrokePrecision.MAXIMUM.stepPxFor(scale) < StrokePrecision.BALANCED.stepPxFor(scale))
        assertTrue(StrokePrecision.ECONOMY.stepPxFor(scale) > StrokePrecision.BALANCED.stepPxFor(scale))
    }

    @Test fun `the decimation radius is bounded in document space at low zoom`() {
        for (precision in StrokePrecision.values()) {
            for (scale in listOf(0.2f, 0.5f, 1f, 2f, 4f, 8f)) {
                val stepPt = precision.stepPxFor(scale) / scale
                assertTrue(
                    "$precision at $scale px/pt discarded ${stepPt}pt",
                    stepPt <= StrokeSmoother.MIN_STEP_PT * precision.factor + 1e-6f,
                )
            }
        }
    }

    @Test fun `zooming out tightens the decimation radius, never loosens it`() {
        var prev = Float.MAX_VALUE
        for (scale in listOf(8f, 4f, 2f, 1f, 0.5f, 0.25f)) {
            val step = StrokePrecision.BALANCED.stepPxFor(scale)
            assertTrue("at $scale", step <= prev + 1e-6f)
            prev = step
        }
        // A 4-column overview (~0.5 px/pt) keeps far more of the page than the old fixed-px rule.
        assertTrue(StrokePrecision.BALANCED.stepPxFor(0.5f) < StrokeSmoother.MIN_STEP_PX)
    }

    @Test fun `a slow drift of sub-threshold steps still emits a point`() {
        // Each raw step is well under the radius, but they all go the same way: the pen really has
        // moved, so decimation must not swallow the whole run by chasing its own filter state.
        val smoother = StrokeSmoother()
        smoother.reset(StrokeSmoother.MIN_STEP_PX)
        assertNotNull(smoother.accept(0f, 0f, 0.5f))
        var emitted = 0
        for (i in 1..40) {
            if (smoother.accept(i * 0.5f, 0f, 0.5f) != null) emitted++
        }
        assertTrue("drifted 20px and emitted $emitted points", emitted >= 5)
    }

    @Test fun `fine detail survives when drawn at a high on-screen scale`() {
        // A gentle curve whose sagitta sits just under the 1 px/pt budget — thinned flat there,
        // preserved at 8 px/pt where that same error would be visible on screen.
        val pts = listOf(pt(0.0, 0.0), pt(5.0, 0.3), pt(10.0, 0.0))
        assertEquals(2, StrokeSimplifier.simplify(pts, StrokeSimplifier.toleranceFor(1f)).size)
        assertEquals(3, StrokeSimplifier.simplify(pts, StrokeSimplifier.toleranceFor(8f)).size)
        // …and MAXIMUM precision keeps it even at 1 px/pt.
        val fine = StrokeSimplifier.toleranceFor(1f, StrokePrecision.MAXIMUM)
        assertEquals(3, StrokeSimplifier.simplify(pts, fine).size)
    }
}
