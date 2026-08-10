package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/** The setsquare/compass projection maths — the part that makes a guide rule a stroke straight. */
class DrawingGuideTest {

    private val eps = 1e-6

    // --- setsquare -------------------------------------------------------------------------------

    @Test
    fun `setsquare pulls a nearby point onto its long leg`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = 0.0)
        val (px, py) = g.project(50.0, 5.0)
        assertEquals(50.0, px, eps)
        assertEquals(0.0, py, eps)
    }

    @Test
    fun `setsquare leaves a far point alone`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = 0.0)
        val far = g.project(50.0, 400.0)
        assertEquals(50.0 to 400.0, far)
    }

    @Test
    fun `setsquare rules along its hypotenuse too`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = 0.0)
        val c = g.corners()
        // The midpoint of the hypotenuse, nudged off it, must come back onto the line.
        val mx = (c[1].first + c[2].first) / 2
        val my = (c[1].second + c[2].second) / 2
        val (px, py) = g.project(mx + 4.0, my + 4.0)
        assertEquals(mx, px, 5.0)
        assertEquals(my, py, 5.0)
        assertTrue(hypot(px - mx, py - my) < 6.0)
    }

    @Test
    fun `a rotated setsquare rules along its rotated leg`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = PI / 2)
        // Long leg now runs down +Y; a point beside it snaps back to x = 0.
        val (px, py) = g.project(6.0, 40.0)
        assertEquals(0.0, px, eps)
        assertEquals(40.0, py, eps)
    }

    @Test
    fun `aiming a setsquare sets its angle and length, and snaps when asked`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = 0.0)
        val free = g.aimedAt(0.0, 60.0, snapAngle = false)
        assertEquals(PI / 2, free.angle, eps)
        assertEquals(60.0, free.size, eps)
        // 20° aims onto the nearest 15° step.
        val snapped = g.aimedAt(100.0, 100.0, snapAngle = true)
        assertEquals(PI / 4, snapped.angle, eps)
    }

    @Test
    fun `aiming never shrinks a setsquare below the minimum`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0)
        assertEquals(DrawingGuide.MIN_SIZE_PT, g.aimedAt(0.5, 0.0, false).size, eps)
    }

    @Test
    fun `setsquare containment covers the body but not the outside of an edge`() {
        val g = DrawingGuide.Setsquare(x = 0.0, y = 0.0, size = 100.0, angle = 0.0)
        assertTrue(g.contains(20.0, 10.0))
        // Just outside the long leg — the side you rule along — must not count as a grab.
        assertTrue(!g.contains(20.0, -3.0))
        assertTrue(!g.contains(200.0, 200.0))
    }

    // --- compass ---------------------------------------------------------------------------------

    @Test
    fun `compass pulls a nearby point onto its circumference`() {
        val g = DrawingGuide.Compass(x = 0.0, y = 0.0, radius = 50.0)
        val (px, py) = g.project(56.0, 0.0)
        assertEquals(50.0, px, eps)
        assertEquals(0.0, py, eps)
    }

    @Test
    fun `compass leaves points far from the circumference alone`() {
        val g = DrawingGuide.Compass(x = 0.0, y = 0.0, radius = 50.0)
        assertEquals(5.0 to 0.0, g.project(5.0, 0.0))
        assertEquals(200.0 to 0.0, g.project(200.0, 0.0))
    }

    @Test
    fun `compass at dead centre is left alone rather than pushed in an arbitrary direction`() {
        val g = DrawingGuide.Compass(x = 10.0, y = 10.0, radius = 50.0)
        assertEquals(10.0 to 10.0, g.project(10.0, 10.0))
    }

    @Test
    fun `opening a compass sets the radius to the drag distance`() {
        val g = DrawingGuide.Compass(x = 0.0, y = 0.0, radius = 50.0)
        assertEquals(30.0, g.openedTo(0.0, 30.0).radius, eps)
        assertEquals(DrawingGuide.MIN_SIZE_PT, g.openedTo(1.0, 1.0).radius, eps)
    }

    // --- shared ----------------------------------------------------------------------------------

    @Test
    fun `moving a guide displaces its anchor and nothing else`() {
        val s = DrawingGuide.Setsquare(x = 1.0, y = 2.0, size = 90.0, angle = 0.3)
        val moved = s.moved(10.0, -5.0) as DrawingGuide.Setsquare
        assertEquals(11.0, moved.x, eps)
        assertEquals(-3.0, moved.y, eps)
        assertEquals(90.0, moved.size, eps)
        assertEquals(0.3, moved.angle, eps)
    }

    @Test
    fun `closest point on a segment clamps to its ends`() {
        val before = DrawingGuide.closestOnSegment(-10.0, 0.0, 0.0, 0.0, 10.0, 0.0)
        assertEquals(0.0 to 0.0, before)
        val after = DrawingGuide.closestOnSegment(99.0, 0.0, 0.0, 0.0, 10.0, 0.0)
        assertEquals(10.0 to 0.0, after)
        val degenerate = DrawingGuide.closestOnSegment(5.0, 5.0, 3.0, 3.0, 3.0, 3.0)
        assertEquals(3.0 to 3.0, degenerate)
    }

    @Test
    fun `placing a guide yields the right kind, and NONE yields nothing`() {
        assertNull(GuideKind.NONE.place(100.0, 100.0))
        assertTrue(GuideKind.SETSQUARE.place(100.0, 100.0) is DrawingGuide.Setsquare)
        val compass = GuideKind.COMPASS.place(100.0, 100.0)
        assertNotNull(compass)
        assertEquals(100.0, compass!!.x, eps)
        assertEquals(100.0, compass.y, eps)
    }
}
