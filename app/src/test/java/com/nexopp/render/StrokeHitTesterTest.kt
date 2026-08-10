package com.nexopp.render

import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHitTesterTest {

    private fun stroke(vararg pts: Triple<Double, Double, Double>) =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", pts.map { StrokePoint(it.first, it.second, it.third) }, false)

    @Test fun distanceToSegmentClampsToEndpoints() {
        // Horizontal segment (0,0)-(10,0); point beyond the end projects onto the endpoint.
        assertEquals(5.0, StrokeHitTester.pointSegmentDistance(5.0, 5.0, 0.0, 0.0, 10.0, 0.0), 1e-9)
        assertEquals(5.0, StrokeHitTester.pointSegmentDistance(15.0, 0.0, 0.0, 0.0, 10.0, 0.0), 1e-9)
    }

    @Test fun degenerateSegmentIsPointDistance() {
        assertEquals(5.0, StrokeHitTester.pointSegmentDistance(3.0, 4.0, 0.0, 0.0, 0.0, 0.0), 1e-9)
    }

    @Test fun hitWhenDiscReachesTheStroke() {
        val s = stroke(0.0 to 0.0 to 1.0, 10.0 to 0.0 to 1.0)
        // 4px away, radius 4 + half-width 0.5 = reach 4.5 >= 4: hit.
        assertTrue(StrokeHitTester.hits(s, 5.0, 4.0, radius = 4.0))
    }

    @Test fun missWhenDiscFallsShort() {
        val s = stroke(0.0 to 0.0 to 1.0, 10.0 to 0.0 to 1.0)
        // 10px away, radius 4 + half-width 0.5 = reach 4.5 < 10: miss.
        assertFalse(StrokeHitTester.hits(s, 5.0, 10.0, radius = 4.0))
    }

    @Test fun singlePointStrokeIsHittable() {
        val dot = stroke(5.0 to 5.0 to 2.0)
        assertTrue(StrokeHitTester.hits(dot, 5.0, 6.0, radius = 0.5)) // 1px away, reach 0.5 + 1.0
        assertFalse(StrokeHitTester.hits(dot, 5.0, 20.0, radius = 0.5))
    }

    @Test fun emptyStrokeIsNeverHit() {
        assertFalse(StrokeHitTester.hits(stroke(), 0.0, 0.0, radius = 100.0))
    }
}

private infix fun Pair<Double, Double>.to(w: Double) = Triple(first, second, w)
