package com.xopp.android.ui

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialPaletteHitTestTest {

    private val geometry = RadialPaletteGeometry()
    private val palette = RadialPalette.default()

    /** A point [radius] away from the origin at [degrees] clockwise from 12 o'clock. */
    private fun at(degrees: Float, radius: Float): Pair<Float, Float> {
        val rad = Math.toRadians(degrees.toDouble())
        return (radius * sin(rad)).toFloat() to (-radius * cos(rad)).toFloat()
    }

    private fun hit(degrees: Float, radius: Float): RadialHit {
        val (x, y) = at(degrees, radius)
        return palette.hitTest(0f, 0f, x, y, geometry)
    }

    private fun slot(degrees: Float, radius: Float): RadialSlot =
        (hit(degrees, radius) as RadialHit.Slot).slot

    @Test
    fun `the dead zone cancels, up to and including its edge`() {
        assertEquals(RadialHit.Cancel, palette.hitTest(100f, 100f, 100f, 100f, geometry))
        assertEquals(RadialHit.Cancel, hit(37f, geometry.deadZoneRadius - 1f))
        assertEquals(RadialHit.Cancel, hit(37f, geometry.deadZoneRadius))
    }

    @Test
    fun `just past the dead zone lands on the inner ring`() {
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(0f, geometry.deadZoneRadius + 1f))
    }

    @Test
    fun `straight up is slot zero of either ring`() {
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(0f, 100f))
        assertEquals(RadialSlot(RadialRing.OUTER, 0), slot(0f, 200f))
    }

    @Test
    fun `inner slots run clockwise at 45 degrees apart`() {
        for (i in 0 until 8) {
            assertEquals(RadialSlot(RadialRing.INNER, i), slot(i * 45f, 100f))
        }
    }

    @Test
    fun `outer slots run clockwise at 22 and a half degrees apart`() {
        for (i in 0 until 16) {
            assertEquals(RadialSlot(RadialRing.OUTER, i), slot(i * 22.5f, 200f))
        }
    }

    @Test
    fun `slot zero wraps across 0 and 360 degrees`() {
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(359f, 100f))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(1f, 100f))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(360f, 100f))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(-1f, 100f))
    }

    @Test
    fun `a wedge boundary falls into the higher-numbered slot`() {
        // The inner ring's slot 0 spans (-22.5, 22.5]; 22.5 exactly is the start of slot 1.
        assertEquals(RadialSlot(RadialRing.INNER, 1), slot(22.5f, 100f))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(22.4f, 100f))
    }

    @Test
    fun `the ring boundary belongs to the inner ring`() {
        assertEquals(RadialRing.INNER, slot(0f, geometry.innerRingRadius).ring)
        assertEquals(RadialRing.OUTER, slot(0f, geometry.innerRingRadius + 1f).ring)
    }

    @Test
    fun `an overshooting flick clamps to the outer ring`() {
        val far = slot(90f, geometry.outerRingRadius * 10f)
        assertEquals(RadialRing.OUTER, far.ring)
        assertEquals(slot(90f, 200f), far)
    }

    @Test
    fun `the hit carries the action, or null for an empty slot`() {
        val filled = hit(0f, 100f) as RadialHit.Slot
        assertEquals(PaletteAction.SelectTool(EditorTool.PEN), filled.action)

        // The default palette fills the outer ring only as far as PEN_COLORS reaches.
        val empty = RadialPalette().hitTest(0f, 0f, 0f, -100f, geometry) as RadialHit.Slot
        assertNull(empty.action)
    }

    @Test
    fun `the anchor is respected, not just the origin`() {
        val above = palette.hitTest(500f, 400f, 500f, 300f, geometry) as RadialHit.Slot
        assertEquals(RadialSlot(RadialRing.INNER, 0), above.slot)
    }

    @Test
    fun `slot centres round-trip through the hit test`() {
        palette.slots().forEach { (slot, _) ->
            val radius = if (slot.ring == RadialRing.INNER) 100f else 200f
            assertEquals(slot, slot(slot.centerDegrees(), radius))
        }
    }

    @Test
    fun `geometry rejects out-of-order radii`() {
        assertTrue(runCatching { RadialPaletteGeometry(50f, 40f, 300f) }.isFailure)
        assertTrue(runCatching { RadialPaletteGeometry(50f, 100f, 60f) }.isFailure)
        assertTrue(runCatching { RadialPaletteGeometry(-1f, 100f, 200f) }.isFailure)
    }
}
