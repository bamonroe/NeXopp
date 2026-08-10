package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialPaletteTest {

    @Test
    fun `a fresh palette is empty and correctly sized`() {
        val p = RadialPalette()
        assertEquals(8, p.inner.size)
        assertEquals(16, p.outer.size)
        assertTrue(p.isEmpty)
        assertEquals(0, p.filledCount)
    }

    @Test
    fun `assigning a slot leaves the rest untouched`() {
        val slot = RadialSlot(RadialRing.INNER, 3)
        val p = RadialPalette().with(slot, PaletteAction.Undo)
        assertEquals(PaletteAction.Undo, p[slot])
        assertEquals(1, p.filledCount)
        assertNull(p[RadialSlot(RadialRing.INNER, 2)])
        assertFalse(p.isEmpty)
    }

    @Test
    fun `clearing a slot empties only that slot`() {
        val a = RadialSlot(RadialRing.OUTER, 0)
        val b = RadialSlot(RadialRing.OUTER, 1)
        val p = RadialPalette()
            .with(a, PaletteAction.SetColor(0xFF112233.toInt()))
            .with(b, PaletteAction.Redo)
            .without(a)
        assertNull(p[a])
        assertEquals(PaletteAction.Redo, p[b])
    }

    @Test
    fun `edits do not mutate the original palette`() {
        val slot = RadialSlot(RadialRing.INNER, 0)
        val original = RadialPalette()
        original.with(slot, PaletteAction.Undo)
        assertNull(original[slot])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a slot index past the ring is rejected`() {
        RadialSlot(RadialRing.INNER, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative slot index is rejected`() {
        RadialSlot(RadialRing.OUTER, -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a ring of the wrong length is rejected`() {
        RadialPalette(inner = List(4) { null })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a width outside the toolbar bounds is rejected`() {
        PaletteAction.SetWidth(PEN_WIDTH_MAX + 1f)
    }

    @Test
    fun `the default palette fills the inner ring and maps the pen colours outward`() {
        val p = RadialPalette.default()
        assertTrue(p.inner.all { it != null })
        assertEquals(PaletteAction.SelectTool(EditorTool.PEN), p[RadialSlot(RadialRing.INNER, 0)])
        val colors = p.outer.filterIsInstance<PaletteAction.SetColor>().map { it.argb }
        assertEquals(PEN_COLORS.take(RadialRing.OUTER.slotCount), colors)
    }

    @Test
    fun `slots enumerates every position, inner ring first`() {
        val all = RadialPalette.default().slots()
        assertEquals(24, all.size)
        assertEquals(RadialSlot(RadialRing.INNER, 0), all.first().first)
        assertEquals(RadialSlot(RadialRing.OUTER, 15), all.last().first)
        assertEquals(RadialPalette.default().filledCount, all.count { it.second != null })
    }
}
