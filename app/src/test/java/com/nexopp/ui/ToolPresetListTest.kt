package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure list edits behind the presets popup: save, overwrite, reorder, delete. */
class ToolPresetListTest {

    private fun preset(id: String) = ToolPreset(id, id, EditorTool.PEN, 0xFF000000.toInt(), 1f)

    private val list = listOf(preset("a"), preset("b"), preset("c"))

    @Test
    fun `a new preset is appended`() {
        assertEquals(list + preset("d"), addToolPreset(list, preset("d")))
    }

    @Test
    fun `saving under an existing id overwrites in place`() {
        val renamed = preset("b").copy(name = "Beta")
        val out = addToolPreset(list, renamed)
        assertEquals(3, out.size)
        assertEquals("Beta", out[1].name)
    }

    @Test
    fun `remove drops just that preset`() {
        assertEquals(listOf(preset("a"), preset("c")), removeToolPreset(list, "b"))
        assertEquals(list, removeToolPreset(list, "nope"))
    }

    @Test
    fun `move reorders, and out-of-range moves are no-ops`() {
        assertEquals(listOf(preset("b"), preset("a"), preset("c")), moveToolPreset(list, 0, 1))
        assertEquals(list, moveToolPreset(list, 0, -1))
        assertEquals(list, moveToolPreset(list, 2, 1))
        assertEquals(list, moveToolPreset(list, 9, 1))
    }

    @Test
    fun `overwrite rewrites the tool but keeps the id, name and position`() {
        val captured = ToolPreset("live", "Live", EditorTool.HIGHLIGHTER, 0xFFFF0000.toInt(), 8f)
        val out = overwriteToolPreset(list, "b", captured)
        assertEquals(3, out.size)
        assertEquals("b", out[1].id)
        assertEquals("b", out[1].name)
        assertEquals(EditorTool.HIGHLIGHTER, out[1].tool)
        assertEquals(8f, out[1].widthPt, 0f)
        assertEquals(listOf(preset("a"), preset("c")), listOf(out[0], out[2]))
    }

    @Test
    fun `overwriting an unknown id is a no-op`() {
        assertEquals(list, overwriteToolPreset(list, "nope", preset("x")))
    }

    @Test
    fun `rename keeps the id and the position`() {
        val out = renameToolPreset(list, "a", "Fine")
        assertEquals("a", out[0].id)
        assertEquals("Fine", out[0].name)
    }
}
