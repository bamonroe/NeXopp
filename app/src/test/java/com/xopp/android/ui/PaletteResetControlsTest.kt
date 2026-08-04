package com.xopp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bulk palette edits behind the editor's reset/clear buttons, and the prose they show. */
class PaletteResetControlsTest {
    @Test
    fun `cleared empties every slot but keeps the name`() {
        val palette = RadialPalette.default().copy(name = "Left hand")
        val cleared = palette.cleared()
        assertEquals(0, cleared.filledCount)
        assertTrue(cleared.isEmpty)
        assertEquals("Left hand", cleared.name)
    }

    @Test
    fun `default fills slots so reset is not a no-op on an empty palette`() {
        assertNotEquals(0, RadialPalette.default().filledCount)
    }

    @Test
    fun `summary counts filled slots out of the total`() {
        val total = RadialRing.INNER.slotCount + RadialRing.OUTER.slotCount
        val one = RadialPalette().with(RadialSlot(RadialRing.INNER, 0), PaletteAction.Undo)
        assertEquals("1 of $total slots assigned.", paletteFilledSummary(one))
    }

    @Test
    fun `summary calls out an entirely empty palette`() {
        assertTrue(paletteFilledSummary(RadialPalette()).startsWith("No slots assigned"))
    }

    @Test
    fun `each bulk edit has its own confirm wording`() {
        val reset = paletteBulkEditPrompt(PaletteBulkEdit.RESET)
        val clear = paletteBulkEditPrompt(PaletteBulkEdit.CLEAR)
        assertNotEquals(reset, clear)
        assertTrue(reset.isNotBlank() && clear.isNotBlank())
    }
}
