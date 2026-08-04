package com.xopp.android.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placement rules the renderer draws by: anchor clamping and where each slot's mark lands. */
class RadialPaletteLayoutTest {

    private val geometry = RadialPaletteGeometry()
    private val inset = geometry.outerRingRadius + DEFAULT_ANCHOR_MARGIN

    @Test
    fun `an anchor well inside the view is left alone`() {
        val p = clampAnchor(500f, 600f, 1000f, 1200f, geometry)
        assertEquals(500f, p.x, 0.01f)
        assertEquals(600f, p.y, 0.01f)
    }

    @Test
    fun `an anchor near an edge is pulled in far enough to fit the outer ring`() {
        val p = clampAnchor(10f, 1190f, 1000f, 1200f, geometry)
        assertEquals(inset, p.x, 0.01f)
        assertEquals(1200f - inset, p.y, 0.01f)
    }

    @Test
    fun `a view too small for the menu centres it instead`() {
        val p = clampAnchor(10f, 10f, 200f, 300f, geometry)
        assertEquals(100f, p.x, 0.01f)
        assertEquals(150f, p.y, 0.01f)
    }

    @Test
    fun `slot zero is drawn straight up and slot marks ring the anchor clockwise`() {
        val up = RadialSlot(RadialRing.INNER, 0).drawCenter(100f, 100f, geometry)
        val r = slotDrawRadius(RadialRing.INNER, geometry)
        assertEquals(100f, up.x, 0.01f)
        assertEquals(100f - r, up.y, 0.01f)

        val right = RadialSlot(RadialRing.INNER, 2).drawCenter(100f, 100f, geometry)
        assertEquals(100f + r, right.x, 0.01f)
        assertEquals(100f, right.y, 0.01f)
    }

    @Test
    fun `each ring draws inside the band the flick is measured against`() {
        for (ring in RadialRing.entries) {
            val r = slotDrawRadius(ring, geometry)
            val hit = RadialPalette.default().hitTest(0f, 0f, 0f, -r, geometry)
            assertEquals(ring, (hit as RadialHit.Slot).slot.ring)
        }
    }

    @Test
    fun `a mark's drawn position hit-tests back to its own slot`() {
        for ((slot, _) in RadialPalette.default().slots()) {
            val c = slot.drawCenter(400f, 400f, geometry)
            val hit = RadialPalette.default().hitTest(400f, 400f, c.x, c.y, geometry)
            assertEquals(slot, (hit as RadialHit.Slot).slot)
        }
    }

    @Test
    fun `a menu summoned in a corner hit-tests around the clamped centre it draws at`() {
        val anchor = clampAnchor(4f, 6f, 1000f, 800f, geometry)
        for ((slot, _) in RadialPalette.default().slots()) {
            val c = slot.drawCenter(anchor.x, anchor.y, geometry)
            val hit = RadialPalette.default().hitTest(anchor.x, anchor.y, c.x, c.y, geometry)
            assertEquals(slot, (hit as RadialHit.Slot).slot)
        }
    }

    @Test
    fun `clamping an already-clamped anchor leaves it where it was`() {
        val once = clampAnchor(4f, 6f, 1000f, 800f, geometry)
        assertEquals(once, clampAnchor(once.x, once.y, 1000f, 800f, geometry))
    }

    @Test
    fun `colour slots carry a swatch and the rest carry a short glyph`() {
        assertEquals(0xFFE00000.toInt(), PaletteAction.SetColor(0xFFE00000.toInt()).face().swatchArgb)
        val undo = PaletteAction.Undo.face()
        assertTrue(undo.swatchArgb == null && undo.glyph.isNotEmpty())
        for ((_, action) in RadialPalette.default().slots()) {
            val face = action?.face() ?: continue
            assertTrue("glyph too long: ${face.glyph}", face.glyph.length <= 2)
        }
    }

    @Test
    fun `marks of one ring never overlap their neighbours`() {
        for (ring in RadialRing.entries) {
            val a = RadialSlot(ring, 0).drawCenter(0f, 0f, geometry)
            val b = RadialSlot(ring, 1).drawCenter(0f, 0f, geometry)
            assertTrue(abs(a.x - b.x) + abs(a.y - b.y) > 40f)
        }
    }
}
