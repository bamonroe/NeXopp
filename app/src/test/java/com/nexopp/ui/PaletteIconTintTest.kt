package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preset-coloured slot icon: which actions tint, and staying readable on the dark disc. */
class PaletteIconTintTest {

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }

    @Test
    fun `only preset slots tint`() {
        val colors = mapOf("p1" to 0xFFCC0000.toInt())
        assertNull(PaletteAction.Undo.iconTintArgb(colors))
        assertNull(PaletteAction.SelectTool(EditorTool.PEN).iconTintArgb(colors))
        assertEquals(
            legibleOnSlot(0xFFCC0000.toInt()),
            PaletteAction.ApplyPreset("p1").iconTintArgb(colors),
        )
    }

    @Test
    fun `an unknown preset falls back to the plain icon`() {
        assertNull(PaletteAction.ApplyPreset("gone").iconTintArgb(emptyMap()))
    }

    @Test
    fun `a light colour keeps its hue and is forced opaque`() {
        assertEquals(0xFFFFEE00.toInt(), legibleOnSlot(0x40FFEE00.toInt()))
    }

    @Test
    fun `dark colours are lifted clear of the slot disc`() {
        for (dark in listOf(0xFF000000.toInt(), 0xFF101010.toInt(), 0xFF000080.toInt())) {
            val lifted = legibleOnSlot(dark)
            assertTrue("$dark stayed dark", luminance(lifted) >= 0.44)
            assertEquals(0xFF, (lifted ushr 24) and 0xFF)
        }
    }

    @Test
    fun `lifting a hue keeps its channel ordering`() {
        val lifted = legibleOnSlot(0xFF200000.toInt())
        val r = (lifted shr 16) and 0xFF
        val g = (lifted shr 8) and 0xFF
        assertTrue("red should still dominate", r > g)
    }
}
