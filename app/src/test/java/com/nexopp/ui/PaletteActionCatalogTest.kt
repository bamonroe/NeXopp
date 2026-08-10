package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slot picker's catalogue: every fixed action reachable, every one of them labelled. */
class PaletteActionCatalogTest {

    @Test
    fun `offers every tool as both select and toggle`() {
        val groups = paletteActionGroups().associateBy { it.title }
        val select = groups.getValue("Select tool").choices.map { it.action }
        val toggle = groups.getValue("Toggle tool").choices.map { it.action }
        assertEquals(EditorTool.entries.map { PaletteAction.SelectTool(it) }, select)
        assertEquals(EditorTool.entries.map { PaletteAction.ToggleTool(it) }, toggle)
    }

    @Test
    fun `offers every page operation`() {
        val page = paletteActionGroups().first { it.title == "Page" }.choices.map { it.action }
        assertEquals(PalettePageOp.entries.map { PaletteAction.Page(it) }, page)
    }

    @Test
    fun `offers the edit actions`() {
        val edit = paletteActionGroups().first { it.title == "Edit" }.choices.map { it.action }
        assertEquals(listOf(PaletteAction.Undo, PaletteAction.Redo, PaletteAction.ToggleFullPage), edit)
    }

    @Test
    fun `every choice carries a non-blank label`() {
        val labels = paletteActionGroups().flatMap { it.choices }.map { it.label }
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun `value-carrying actions describe their value`() {
        assertEquals("Colour #FF0000", PaletteAction.SetColor(0xFFFF0000.toInt()).describeAction())
        assertEquals("Width 2.5 pt", PaletteAction.SetWidth(2.5f).describeAction())
        assertEquals("Select pen", PaletteAction.SelectTool(EditorTool.PEN).describeAction())
    }

    @Test
    fun `picking an action and clearing it round-trip through the palette`() {
        val slot = RadialSlot(RadialRing.OUTER, 3)
        val assigned = RadialPalette().with(slot, PaletteAction.Undo)
        assertEquals(PaletteAction.Undo, assigned[slot])
        assertEquals(null, assigned.with(slot, null)[slot])
    }
}
