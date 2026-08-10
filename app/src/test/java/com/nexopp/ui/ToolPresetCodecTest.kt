package com.nexopp.ui

import com.nexopp.format.model.LineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preset list's SharedPreferences encoding: exact round-trips, and tolerance of foreign strings. */
class ToolPresetCodecTest {

    private val presets = listOf(
        ToolPreset("fine-black", "Fine black", EditorTool.PEN, 0xFF000000.toInt(), 0.85f),
        ToolPreset(
            id = "marker",
            name = "Marker",
            tool = EditorTool.HIGHLIGHTER,
            colorArgb = 0xFFFFFF00.toInt(),
            widthPt = 8f,
            lineStyle = LineStyle.DASHED,
            fillEnabled = true,
            fillAlpha = 90,
        ),
    )

    @Test
    fun `empty list round-trips`() {
        assertEquals(emptyList<ToolPreset>(), decodeToolPresets(encodeToolPresets(emptyList())))
    }

    @Test
    fun `presets round-trip with every field`() {
        assertEquals(presets, decodeToolPresets(encodeToolPresets(presets)))
    }

    @Test
    fun `separators in a name do not break the list`() {
        val odd = listOf(presets[0].copy(name = "a;b|c"))
        val back = decodeToolPresets(encodeToolPresets(odd))
        assertEquals(1, back.size)
        assertEquals("a b c", back[0].name)
    }

    @Test
    fun `blank or absent raw decodes to nothing`() {
        assertTrue(decodeToolPresets(null).isEmpty())
        assertTrue(decodeToolPresets("  ").isEmpty())
    }

    @Test
    fun `an unreadable preset is dropped and the rest survive`() {
        val raw = encodeToolPresets(presets)
        assertEquals(presets.drop(1), decodeToolPresets("broken;;NOT_A_TOOL;x;y;z;0;0|" + raw.substringAfter('|')))
        assertEquals(presets.take(1), decodeToolPresets(raw.substringBefore('|') + "|too;few;fields"))
    }

    @Test
    fun `a preset palette action round-trips through the palette codec`() {
        val palette = RadialPalette().with(
            RadialSlot(RadialRing.INNER, 0),
            PaletteAction.ApplyPreset("marker"),
        )
        assertEquals(palette, decodeRadialPalette(encodeRadialPalette(palette)))
    }

    @Test
    fun `a preset slot names the preset, or says it is gone`() {
        val action = PaletteAction.ApplyPreset("marker")
        assertEquals("Preset Marker", action.describeAction(presets))
        assertEquals("Preset (deleted)", action.describeAction(emptyList()))
    }

    @Test
    fun `a preset-slot palette action round-trips through the palette codec`() {
        val palette = RadialPalette()
            .with(RadialSlot(RadialRing.INNER, 0), PaletteAction.ApplyPresetSlot(0))
            .with(RadialSlot(RadialRing.OUTER, 1), PaletteAction.ApplyPresetSlot(2))
        assertEquals(palette, decodeRadialPalette(encodeRadialPalette(palette)))
    }

    @Test
    fun `a preset-slot action names whatever preset sits at that position`() {
        assertEquals("Preset 1 (Fine black)", PaletteAction.ApplyPresetSlot(0).describeAction(presets))
        assertEquals("Preset 2 (Marker)", PaletteAction.ApplyPresetSlot(1).describeAction(presets))
        assertEquals("Preset 9 (empty)", PaletteAction.ApplyPresetSlot(8).describeAction(presets))
        assertEquals("★1", PaletteAction.ApplyPresetSlot(0).face().glyph)
    }

    @Test
    fun `preset positions become picker choices`() {
        val group = paletteActionGroups(presets).first { it.title == "Preset slot" }
        assertEquals(presets.indices.map { PaletteAction.ApplyPresetSlot(it) }, group.choices.map { it.action })
        assertEquals(listOf("Preset 1: Fine black", "Preset 2: Marker"), group.choices.map { it.label })
        assertTrue(paletteActionGroups().none { it.title == "Preset slot" })
    }

    @Test
    fun `saved presets become picker choices`() {
        val group = paletteActionGroups(presets).first { it.title == "Preset" }
        assertEquals(presets.map { PaletteAction.ApplyPreset(it.id) }, group.choices.map { it.action })
        assertEquals(listOf("1. Fine black", "2. Marker"), group.choices.map { it.label })
        assertTrue(paletteActionGroups().none { it.title == "Preset" })
    }

    @Test
    fun `an out-of-range width is coerced rather than dropped`() {
        val raw = "wide;Wide;PEN;-1;9999;PLAIN;0;128"
        assertEquals(PEN_WIDTH_MAX, decodeToolPresets(raw).single().widthPt)
    }
}
