package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Pure geometry tests for the shape tools (line / arrow / rectangle / ellipse). */
class ShapeBuilderTest {

    @Test fun lineIsTwoPointsAtTheConstantWidth() {
        val pts = ShapeBuilder.build(ShapeKind.LINE, 1.0, 2.0, 5.0, 8.0, widthPt = 3.0)
        assertEquals(2, pts.size)
        assertEquals(1.0, pts[0].x, 1e-9); assertEquals(2.0, pts[0].y, 1e-9)
        assertEquals(5.0, pts[1].x, 1e-9); assertEquals(8.0, pts[1].y, 1e-9)
        assertTrue(pts.all { it.width == 3.0 })
    }

    @Test fun rectangleIsAClosedBoundingBox() {
        // Drag from bottom-right to top-left still yields the normalised box.
        val pts = ShapeBuilder.build(ShapeKind.RECTANGLE, 10.0, 20.0, 0.0, 0.0, widthPt = 1.0)
        assertEquals(5, pts.size)
        assertEquals(pts.first().x, pts.last().x, 1e-9)
        assertEquals(pts.first().y, pts.last().y, 1e-9)
        val xs = pts.map { it.x }.toSet()
        val ys = pts.map { it.y }.toSet()
        assertEquals(setOf(0.0, 10.0), xs)
        assertEquals(setOf(0.0, 20.0), ys)
    }

    @Test fun arrowHasShaftTipAndTwoBarbs() {
        val pts = ShapeBuilder.build(ShapeKind.ARROW, 0.0, 0.0, 10.0, 0.0, widthPt = 1.0)
        assertEquals(5, pts.size)          // start, tip, barb, tip, barb
        assertEquals(0.0, pts[0].x, 1e-9)  // shaft start
        assertEquals(10.0, pts[1].x, 1e-9) // tip
        assertEquals(10.0, pts[3].x, 1e-9) // tip repeated to trace the second barb
        // Barbs sit behind the tip (smaller x) for a rightward arrow.
        assertTrue(pts[2].x < 10.0)
        assertTrue(pts[4].x < 10.0)
    }

    @Test fun ellipseIsClosedAndInscribedInTheBox() {
        val pts = ShapeBuilder.build(ShapeKind.ELLIPSE, 0.0, 0.0, 20.0, 10.0, widthPt = 1.0)
        assertTrue(pts.size >= 24)
        assertEquals(pts.first().x, pts.last().x, 1e-9) // closed
        assertEquals(pts.first().y, pts.last().y, 1e-9)
        // Every vertex lies on the ellipse: ((x-cx)/rx)^2 + ((y-cy)/ry)^2 == 1.
        val cx = 10.0; val cy = 5.0; val rx = 10.0; val ry = 5.0
        for (p in pts) {
            val v = hypot((p.x - cx) / rx, (p.y - cy) / ry)
            assertEquals(1.0, v, 1e-6)
        }
    }

    @Test fun zeroLengthArrowFallsBackToALine() {
        val pts = ShapeBuilder.build(ShapeKind.ARROW, 4.0, 4.0, 4.0, 4.0, widthPt = 1.0)
        assertEquals(2, pts.size)
    }
}
