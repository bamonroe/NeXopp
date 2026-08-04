package com.xopp.android.ui

import com.xopp.android.format.model.LineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preset is a snapshot of the live pen: capturing then re-applying it has to put the editor back
 * exactly where it was, whatever was changed in between.
 */
class ToolPresetTest {

    private fun state(tool: EditorTool, color: Int, width: Float) =
        EditorUiState(tool, color, width)

    @Test
    fun `capture snapshots the live tool state`() {
        val ui = state(EditorTool.HIGHLIGHTER, 0xFF112233.toInt(), 4.5f)
        ui.lineStyle = LineStyle.DASHED
        val settings = AppSettings(fillEnabled = true, fillAlpha = 128)

        val preset = ToolPreset.capture(ui, settings, "Yellow marker")

        assertEquals(EditorTool.HIGHLIGHTER, preset.tool)
        assertEquals(0xFF112233.toInt(), preset.colorArgb)
        assertEquals(4.5f, preset.widthPt, 0f)
        assertEquals(LineStyle.DASHED, preset.lineStyle)
        assertEquals(128, preset.currentFill)
        assertEquals("yellow-marker", preset.id)
    }

    @Test
    fun `apply restores every captured field after the pen moves on`() {
        val ui = state(EditorTool.PEN, 0xFF000000.toInt(), 1.5f)
        ui.lineStyle = LineStyle.DOTTED
        val preset = ToolPreset.capture(ui, AppSettings(), "Fine black")

        ui.tool = EditorTool.ERASER
        ui.color = 0xFFFF0000.toInt()
        ui.width = 9f
        ui.lineStyle = LineStyle.DASHED

        preset.applyToState(ui)

        assertEquals(EditorTool.PEN, ui.tool)
        assertEquals(0xFF000000.toInt(), ui.color)
        assertEquals(1.5f, ui.width, 0f)
        assertEquals(LineStyle.DOTTED, ui.lineStyle)
    }

    @Test
    fun `apply writes the persisted half back into settings`() {
        val ui = state(EditorTool.PEN, 0xFF00FF00.toInt(), 2.5f)
        val preset = ToolPreset.capture(ui, AppSettings(fillEnabled = true, fillAlpha = 64), "Fill")

        val next = preset.applyToSettings(AppSettings(fillEnabled = false, fillAlpha = 255))

        assertEquals(2.5f, next.lastWidth, 0f)
        assertEquals(0xFF00FF00.toInt(), next.lastColor)
        assertTrue(next.fillEnabled)
        assertEquals(64, next.fillAlpha)
        // The captured colour also lands in the recent-colour history, as a toolbar pick would.
        assertTrue(next.recentColors.contains(0xFF00FF00.toInt()))
    }

    @Test
    fun `fill off yields no canvas fill`() {
        assertNull(ToolPreset("p", "P", EditorTool.PEN, 0, 1f).currentFill)
    }

    @Test
    fun `ids are kebab-case and never empty`() {
        assertEquals("bold-red-pen", ToolPreset.slugId("Bold  Red /Pen"))
        assertEquals("preset", ToolPreset.slugId("!!!"))
    }
}
