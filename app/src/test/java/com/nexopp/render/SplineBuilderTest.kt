package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    @Test
    fun everyPointCarriesTheConstantWidth() {
        val pts = SplineBuilder.build(
            listOf(SplineNode(0.0, 0.0, 10.0, 10.0), SplineNode(80.0, 40.0)),
            w,
        )
        assertTrue(pts.all { it.width == w })
    }
}
