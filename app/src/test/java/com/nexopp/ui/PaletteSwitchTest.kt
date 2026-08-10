package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch-palette slot action: its wire token, its picker entry, and how it reads in prose. */
class PaletteSwitchTest {

    @Test
    fun `switch action round-trips through the codec`() {
        val palette = RadialPalette.default().with(
            RadialSlot(RadialRing.INNER, 0),
            PaletteAction.SwitchPalette("Shapes"),
        )
        assertEquals(palette, decodeRadialPalette(encodeRadialPalette(palette)))
    }

    @Test
    fun `a switch slot survives a whole-list round trip`() {
        val palettes = listOf(
            RadialPalette(name = "Ink").with(RadialSlot(RadialRing.OUTER, 3), PaletteAction.SwitchPalette("Shapes")),
            RadialPalette(name = "Shapes"),
        )
        assertEquals(palettes, decodeRadialPalettes(encodeRadialPalettes(palettes)))
    }

    @Test
    fun `a nameless switch token decodes to an empty slot`() {
        val decoded = decodeRadialPalette("P;palette:,,,,,,,;")
        assertNull(decoded!!.inner[0])
    }

    @Test
    fun `the picker offers one switch choice per palette, but only when there is more than one`() {
        val palettes = listOf(RadialPalette(name = "Ink"), RadialPalette(name = "Shapes"))
        val group = paletteActionGroups(palettes = palettes).first { it.title == "Switch palette" }
        assertEquals(
            listOf(PaletteAction.SwitchPalette("Ink"), PaletteAction.SwitchPalette("Shapes")),
            group.choices.map { it.action },
        )
        assertEquals(listOf("Ink", "Shapes"), group.choices.map { it.label })
        assertTrue(paletteActionGroups(palettes = palettes.take(1)).none { it.title == "Switch palette" })
        assertTrue(paletteActionGroups().none { it.title == "Switch palette" })
    }

    @Test
    fun `a switch action describes its target`() {
        assertEquals("Switch to Shapes", PaletteAction.SwitchPalette("Shapes").describeAction())
    }

    @Test
    fun `activating by name is what the slot resolves to`() {
        val set = PaletteSet(listOf(RadialPalette(name = "Ink"), RadialPalette(name = "Shapes")), 0)
        val target = set.palettes.indexOfFirst { it.name == "Shapes" }
        assertEquals("Shapes", activatePalette(set, target).active.name)
        // A name no palette carries any more resolves to nothing, so the slot is a no-op.
        assertEquals(-1, set.palettes.indexOfFirst { it.name == "Gone" })
    }
}
