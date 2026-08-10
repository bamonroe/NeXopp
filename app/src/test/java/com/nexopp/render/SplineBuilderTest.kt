package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class SplineBuilderTest {

    private val w = 2.0

    @Test
    fun aSingleNodeIsNotAStroke() {
        val pts = SplineBuilder.build(listOf(SplineNode(10.0, 10.0)), w)
        assertEquals(1, pts.size)
    }

    @Test
    fun emptyInputYieldsNoPoints() {
        assertTrue(SplineBuilder.build(emptyList(), w).isEmpty())
    }

    @Test
    fun theCurvePassesThroughEveryAnchor() {
        val nodes = listOf(
            SplineNode(0.0, 0.0, 20.0, 0.0),
            SplineNode(100.0, 50.0, 20.0, 20.0),
            SplineNode(200.0, 0.0),
        )
        val pts = SplineBuilder.build(nodes, w)
        assertEquals(0.0, pts.first().x, 1e-9)
        assertEquals(0.0, pts.first().y, 1e-9)
        assertEquals(200.0, pts.last().x, 1e-9)
        assertEquals(0.0, pts.last().y, 1e-9)
        assertTrue(nodes[1].let { n -> pts.any { abs(it.x - n.x) < 1e-6 && abs(it.y - n.y) < 1e-6 } })
    }

    @Test
    fun zeroTangentsGiveStraightSegments() {
        val pts = SplineBuilder.build(listOf(SplineNode(0.0, 0.0), SplineNode(100.0, 100.0)), w)
        // Every sample sits on y = x, i.e. the straight line between the two anchors.
        assertTrue(pts.all { abs(it.x - it.y) < 1e-9 })
    }

    @Test
    fun aTangentBowsTheCurveOffTheChord() {
        val pts = SplineBuilder.build(
            listOf(SplineNode(0.0, 0.0, 0.0, 60.0), SplineNode(100.0, 0.0, 0.0, -60.0)),
            w,
        )
        assertTrue("expected the handles to push the curve below the chord", pts.any { it.y > 5.0 })
    }

    /**
     * The tangent handle is symmetric about its anchor, so the curve leaves a node in the same
     * direction it arrived — the property that makes the preview readable when a third point pulls
     * the earlier segments around, rather than kinking at the joint.
     */
    @Test
    fun theCurveDoesNotKinkAtAnInteriorNode() {
        val nodes = listOf(
            SplineNode(0.0, 0.0, 30.0, 0.0),
            SplineNode(100.0, 40.0, 30.0, 30.0),
            SplineNode(200.0, 0.0, 30.0, 0.0),
        )
        val pts = SplineBuilder.build(nodes, w)
        val joint = pts.indexOfFirst { abs(it.x - 100.0) < 1e-6 && abs(it.y - 40.0) < 1e-6 }
        assertTrue("the middle anchor should appear in the samples", joint > 0)
        val handle = atan2(nodes[1].ty, nodes[1].tx)
        val inAngle = atan2(pts[joint].y - pts[joint - 1].y, pts[joint].x - pts[joint - 1].x)
        val outAngle = atan2(pts[joint + 1].y - pts[joint].y, pts[joint + 1].x - pts[joint].x)
        // Chords between samples only approximate the tangent, hence the tolerance; a genuine kink
        // would swing one of these by a good fraction of a radian, far outside it.
        assertEquals("the curve should arrive along the handle", handle, inAngle, 0.1)
        assertEquals("and leave along the same handle", handle, outAngle, 0.1)
    }

    /** Segment counts are clamped, so neither a hair-thin nor an enormous span blows up the sampling. */
    @Test
    fun segmentCountsStayWithinTheirBounds() {
        val tiny = SplineBuilder.build(listOf(SplineNode(0.0, 0.0), SplineNode(0.5, 0.0)), w)
        assertEquals(SplineBuilder.MIN_SEGMENTS + 1, tiny.size)
        val huge = SplineBuilder.build(listOf(SplineNode(0.0, 0.0), SplineNode(100_000.0, 0.0)), w)
        assertEquals(SplineBuilder.MAX_SEGMENTS + 1, huge.size)
    }

    @Test
    fun everyPointCarriesTheConstantWidth() {
        val pts = SplineBuilder.build(
            listOf(SplineNode(0.0, 0.0, 10.0, 10.0), SplineNode(80.0, 40.0)),
            w,
        )
        assertTrue(pts.all { it.width == w })
    }
}
