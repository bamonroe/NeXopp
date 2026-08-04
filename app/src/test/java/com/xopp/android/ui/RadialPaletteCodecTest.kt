package com.xopp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The palette's SharedPreferences encoding: exact round-trips, and tolerance of foreign strings. */
class RadialPaletteCodecTest {

    @Test
    fun `default palette round-trips`() {
        val palette = RadialPalette.default()
        assertEquals(palette, decodeRadialPalette(encodeRadialPalette(palette)))
    }

    @Test
    fun `every action kind round-trips`() {
        val palette = RadialPalette(
            name = "Mine",
            inner = listOf(
                PaletteAction.SelectTool(EditorTool.PEN),
                PaletteAction.ToggleTool(EditorTool.ERASER),
                PaletteAction.SetColor(0xFF123456.toInt()),
                PaletteAction.SetWidth(2.25f),
                PaletteAction.Undo,
                PaletteAction.Redo,
                PaletteAction.ToggleFullPage,
                PaletteAction.Page(PalettePageOp.DUPLICATE),
            ),
            outer = List(RadialRing.OUTER.slotCount) { null },
        )
        assertEquals(palette, decodeRadialPalette(encodeRadialPalette(palette)))
    }

    @Test
    fun `empty palette round-trips`() {
        val palette = RadialPalette(name = "Blank")
        val decoded = decodeRadialPalette(encodeRadialPalette(palette))
        assertEquals(palette, decoded)
        assertEquals(0, decoded!!.filledCount)
    }

    @Test
    fun `absent or blank preference decodes to null so the caller picks the fallback`() {
        assertNull(decodeRadialPalette(null))
        assertNull(decodeRadialPalette("   "))
    }

    @Test
    fun `unknown action tokens become empty slots and leave the rest intact`() {
        val raw = "P;tool:PEN,teleport:MARS,tool:NO_SUCH_TOOL,color:zzz,undo,,,;"
        val decoded = decodeRadialPalette(raw)!!
        assertEquals(PaletteAction.SelectTool(EditorTool.PEN), decoded.inner[0])
        assertNull(decoded.inner[1])
        assertNull(decoded.inner[2])
        assertNull(decoded.inner[3])
        assertEquals(PaletteAction.Undo, decoded.inner[4])
    }

    @Test
    fun `short and long rings are padded and truncated to the current slot count`() {
        val short = decodeRadialPalette("P;undo,redo;color:1")!!
        assertEquals(RadialRing.INNER.slotCount, short.inner.size)
        assertEquals(RadialRing.OUTER.slotCount, short.outer.size)
        assertNull(short.inner.last())

        val long = decodeRadialPalette("P;" + List(40) { "undo" }.joinToString(",") + ";")!!
        assertEquals(RadialRing.INNER.slotCount, long.inner.size)
    }

    @Test
    fun `a missing name field falls back to the default name`() {
        assertEquals(RadialPalette.DEFAULT_NAME, decodeRadialPalette(";undo;")!!.name)
    }

    @Test
    fun `an out-of-range width is coerced rather than dropped`() {
        val decoded = decodeRadialPalette("P;width:900;")!!
        assertEquals(PaletteAction.SetWidth(PEN_WIDTH_MAX), decoded.inner[0])
    }

    @Test
    fun `separators in the name do not corrupt the fields`() {
        val palette = RadialPalette(name = "a;b,c", inner = List(8) { PaletteAction.Undo })
        val decoded = decodeRadialPalette(encodeRadialPalette(palette))!!
        assertEquals("a b c", decoded.name)
        assertEquals(8, decoded.filledCount)
    }
}
