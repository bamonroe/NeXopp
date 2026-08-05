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

    /** The radius each ring's marks are drawn at — where a direct hit has to land. */
    private val INNER_MARK = slotDrawRadius(RadialRing.INNER, geometry)
    private val OUTER_MARK = slotDrawRadius(RadialRing.OUTER, geometry)

    @Test
    fun `the dead zone cancels, up to and including its edge`() {
        assertEquals(RadialHit.Cancel, palette.hitTest(100f, 100f, 100f, 100f, geometry))
        assertEquals(RadialHit.Cancel, hit(37f, geometry.deadZoneRadius - 1f))
        assertEquals(RadialHit.Cancel, hit(37f, geometry.deadZoneRadius))
    }

    @Test
    fun `the gap between the dead zone and the marks is not a pick`() {
        // Selection takes a direct hit on the mark; the empty band short of it dismisses instead.
        assertEquals(RadialHit.Outside, hit(0f, geometry.deadZoneRadius + 1f))
    }

    @Test
    fun `straight up is slot zero of either ring`() {
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(0f, INNER_MARK))
        assertEquals(RadialSlot(RadialRing.OUTER, 0), slot(0f, OUTER_MARK))
    }

    @Test
    fun `inner slots run clockwise at 45 degrees apart`() {
        for (i in 0 until 8) {
            assertEquals(RadialSlot(RadialRing.INNER, i), slot(i * 45f, INNER_MARK))
        }
    }

    @Test
    fun `outer slots run clockwise at 22 and a half degrees apart`() {
        for (i in 0 until 16) {
            assertEquals(RadialSlot(RadialRing.OUTER, i), slot(i * 22.5f, OUTER_MARK))
        }
    }

    @Test
    fun `slot zero wraps across 0 and 360 degrees`() {
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(359f, INNER_MARK))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(1f, INNER_MARK))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(360f, INNER_MARK))
        assertEquals(RadialSlot(RadialRing.INNER, 0), slot(-1f, INNER_MARK))
    }

    @Test
    fun `a wedge boundary falls into the higher-numbered slot`() {
        // The inner ring's slot 0 spans (-22.5, 22.5]; 22.5 exactly is the start of slot 1. The
        // wedge only names the candidate now, so this is asserted on the angle mapping itself.
        assertEquals(1, slotIndexAt(22.5f, RadialRing.INNER))
        assertEquals(0, slotIndexAt(22.4f, RadialRing.INNER))
    }

    @Test
    fun `only the marks themselves select, not the band around them`() {
        assertEquals(RadialRing.INNER, slot(0f, INNER_MARK).ring)
        assertEquals(RadialRing.OUTER, slot(0f, OUTER_MARK).ring)
        // Just inside a mark's edge still picks it; a hair beyond it does not.
        assertEquals(RadialRing.INNER, slot(0f, INNER_MARK + INNER_SLOT_MARK_RADIUS - 1f).ring)
        assertEquals(RadialHit.Outside, hit(0f, INNER_MARK + INNER_SLOT_MARK_RADIUS + 1f))
        assertEquals(RadialHit.Outside, hit(0f, geometry.innerRingRadius))
    }

    @Test
    fun `a flick out to the border is off the marks, so it dismisses`() {
        // The outer marks sit well inside the drawn border; reaching the border itself is a miss.
        assertEquals(RadialSlot(RadialRing.OUTER, 4), slot(90f, OUTER_MARK))
        assertEquals(RadialHit.Outside, hit(90f, geometry.dismissRadius))
    }

    @Test
    fun `the hit carries the action, or null for an empty slot`() {
        val filled = hit(0f, INNER_MARK) as RadialHit.Slot
        assertEquals(PaletteAction.SelectTool(EditorTool.PEN), filled.action)

        // The default palette fills the outer ring only as far as PEN_COLORS reaches.
        val empty = RadialPalette().hitTest(0f, 0f, 0f, -INNER_MARK, geometry) as RadialHit.Slot
        assertNull(empty.action)
    }

    @Test
    fun `the anchor is respected, not just the origin`() {
        val above = palette.hitTest(500f, 400f, 500f, 400f - INNER_MARK, geometry) as RadialHit.Slot
        assertEquals(RadialSlot(RadialRing.INNER, 0), above.slot)
    }

    @Test
    fun `slot centres round-trip through the hit test`() {
        palette.slots().forEach { (slot, _) ->
            val radius = if (slot.ring == RadialRing.INNER) INNER_MARK else OUTER_MARK
            assertEquals(slot, slot(slot.centerDegrees(), radius))
        }
    }

    @Test
    fun `geometry rejects out-of-order radii`() {
        assertTrue(runCatching { RadialPaletteGeometry(50f, 40f, 300f) }.isFailure)
        assertTrue(runCatching { RadialPaletteGeometry(50f, 100f, 60f) }.isFailure)
        assertTrue(runCatching { RadialPaletteGeometry(-1f, 100f, 200f) }.isFailure)
        assertTrue(runCatching { RadialPaletteGeometry(50f, 100f, 200f, 150f) }.isFailure)
    }

    @Test
    fun `the dismiss edge sits on the drawn border, with no dead space beyond it`() {
        assertEquals(geometry.outerRingRadius, geometry.dismissRadius)
        // Anywhere off a mark dismisses — this is the "click off it" that closes the menu.
        assertEquals(RadialHit.Outside, hit(0f, geometry.dismissRadius))
        assertEquals(RadialHit.Outside, hit(0f, geometry.dismissRadius + 1f))
        assertEquals(RadialHit.Outside, hit(0f, geometry.outerRingRadius + 20f))
        assertEquals(RadialHit.Outside, hit(135f, 5_000f))
    }
}
