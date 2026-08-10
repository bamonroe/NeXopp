package com.nexopp.ui

import com.nexopp.render.CanvasChrome
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The palette editor's diagram: slot placement, tap mapping, and the fit to its box. */
class PaletteEditorLayoutTest {
    private val size = 600f
    private val palette = RadialPalette.default()

    @Test
    fun `mark radii mirror the canvas renderer`() {
        assertEquals(CanvasChrome.PALETTE_SLOT_PX, slotMarkRadius(RadialRing.INNER), 0f)
        assertEquals(CanvasChrome.PALETTE_SLOT_OUTER_PX, slotMarkRadius(RadialRing.OUTER), 0f)
    }

    @Test
    fun `every slot is placed, inner ring first`() {
        val marks = palette.editorSlots(size)
        assertEquals(RadialRing.INNER.slotCount + RadialRing.OUTER.slotCount, marks.size)
        assertEquals(RadialSlot(RadialRing.INNER, 0), marks.first().slot)
        assertEquals(RadialSlot(RadialRing.OUTER, RadialRing.OUTER.slotCount - 1), marks.last().slot)
        marks.forEach { assertEquals(palette[it.slot], it.action) }
    }

    @Test
    fun `slot zero sits straight up from the centre`() {
        val mark = palette.editorSlots(size).first { it.slot == RadialSlot(RadialRing.INNER, 0) }
        assertTrue(abs(mark.center.x - size / 2f) < 0.01f)
        assertTrue(mark.center.y < size / 2f)
    }

    @Test
    fun `the whole diagram fits inside its box`() {
        palette.editorSlots(size).forEach { mark ->
            assertTrue(mark.center.x - mark.radius >= -0.01f)
            assertTrue(mark.center.x + mark.radius <= size + 0.01f)
            assertTrue(mark.center.y - mark.radius >= -0.01f)
            assertTrue(mark.center.y + mark.radius <= size + 0.01f)
        }
    }

    @Test
    fun `tapping a mark selects its slot`() {
        for (mark in palette.editorSlots(size)) {
            assertEquals(mark.slot, palette.editorSlotAt(mark.center.x, mark.center.y, size))
        }
    }

    @Test
    fun `taps in the dead zone and outside the rings select nothing`() {
        assertNull(palette.editorSlotAt(size / 2f, size / 2f, size))
        assertNull(palette.editorSlotAt(0f, 0f, size))
    }

    @Test
    fun `the diagram scales with its box`() {
        val small = palette.editorSlots(300f).first()
        val large = palette.editorSlots(600f).first()
        assertTrue(abs(large.radius - small.radius * 2f) < 0.01f)
        val slot = palette.editorSlotAt(small.center.x, small.center.y, 300f)
        assertNotNull(slot)
        assertEquals(small.slot, slot)
    }

    @Test
    fun `a diagram wide enough to scale up still lays out`() {
        // A tablet-width settings card scales past 1x; the dismiss radius has to scale with the
        // rest or the geometry's own invariants reject it mid-draw.
        for (edge in listOf(700f, 1200f, 2000f, 4000f)) {
            val marks = palette.editorSlots(edge)
            assertEquals(RadialRing.INNER.slotCount + RadialRing.OUTER.slotCount, marks.size)
            assertEquals(marks.first().slot, palette.editorSlotAt(marks.first().center.x, marks.first().center.y, edge))
        }
    }
}
