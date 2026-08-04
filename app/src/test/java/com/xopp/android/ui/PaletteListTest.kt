package com.xopp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure palette-list edits: adding, renaming, reordering, deleting, and the active index. */
class PaletteListTest {

    private fun setOf(vararg names: String, active: Int = 0) =
        PaletteSet(names.map { RadialPalette(name = it) }, active)

    @Test
    fun `a fresh set holds one default palette`() {
        assertEquals(1, PaletteSet().palettes.size)
        assertEquals(RadialPalette.default(), PaletteSet().active)
    }

    @Test
    fun `adding appends an empty palette and makes it active`() {
        val added = addPalette(setOf("A"))
        assertEquals(2, added.palettes.size)
        assertEquals(1, added.activeIndex)
        assertTrue(added.active.isEmpty)
    }

    @Test
    fun `added palettes get unique names`() {
        val twice = addPalette(addPalette(setOf("A"), "Sketch"), "Sketch")
        assertEquals(listOf("A", "Sketch", "Sketch 2"), twice.palettes.map { it.name })
    }

    @Test
    fun `the last palette cannot be deleted`() {
        val one = setOf("A")
        assertEquals(one, removePalette(one, 0))
    }

    @Test
    fun `deleting before the active entry keeps the same palette active`() {
        val after = removePalette(setOf("A", "B", "C", active = 2), 0)
        assertEquals("C", after.active.name)
    }

    @Test
    fun `deleting the active entry clamps the index into range`() {
        val after = removePalette(setOf("A", "B", active = 1), 1)
        assertEquals(listOf("A"), after.palettes.map { it.name })
        assertEquals(0, after.activeIndex)
    }

    @Test
    fun `moving carries the active index with the moved palette`() {
        val after = movePalette(setOf("A", "B", "C", active = 2), 2, -2)
        assertEquals(listOf("C", "A", "B"), after.palettes.map { it.name })
        assertEquals(0, after.activeIndex)
    }

    @Test
    fun `moving a neighbour shifts the active index`() {
        val after = movePalette(setOf("A", "B", "C", active = 0), 2, -2)
        assertEquals("A", after.active.name)
        assertEquals(1, after.activeIndex)
    }

    @Test
    fun `an out-of-range move is a no-op`() {
        val set = setOf("A", "B")
        assertEquals(set, movePalette(set, 0, -1))
        assertEquals(set, movePalette(set, 1, 1))
    }

    @Test
    fun `renaming keeps slots, rejects blanks and de-duplicates`() {
        val set = PaletteSet(listOf(RadialPalette.default().copy(name = "A"), RadialPalette(name = "B")), 0)
        val renamed = renamePalette(set, 0, "  ")
        assertEquals(RadialPalette.DEFAULT_NAME, renamed.palettes[0].name)
        assertEquals(RadialPalette.default().filledCount, renamed.palettes[0].filledCount)
        assertEquals("A 2", renamePalette(setOf("A", "B"), 1, "A").palettes[1].name)
    }

    @Test
    fun `activate picks a palette, ignoring an index outside the list`() {
        assertEquals(1, activatePalette(setOf("A", "B"), 1).activeIndex)
        assertEquals(0, activatePalette(setOf("A", "B"), 5).activeIndex)
    }

    @Test
    fun `normalizing repairs an empty list and an out-of-range index`() {
        assertEquals(1, PaletteSet(emptyList(), 3).normalized().palettes.size)
        assertEquals(0, PaletteSet(emptyList(), 3).normalized().activeIndex)
        assertEquals(1, setOf("A", "B", active = 9).normalized().activeIndex)
    }

    @Test
    fun `withPaletteAt replaces one entry and ignores a bad index`() {
        val set = setOf("A", "B")
        assertEquals("A", set.withPaletteAt(1, RadialPalette(name = "Z")).palettes[0].name)
        assertEquals("Z", set.withPaletteAt(1, RadialPalette(name = "Z")).palettes[1].name)
        assertEquals(set, set.withPaletteAt(7, RadialPalette(name = "Z")))
    }

    @Test
    fun `a pre-list pref migrates into a one-entry list`() {
        val legacy = encodeRadialPalette(RadialPalette(name = "Mine", inner = List(8) { PaletteAction.Undo }))
        val migrated = migratedPaletteSet(listRaw = null, legacyRaw = legacy, activeIndex = 0)
        assertEquals(listOf("Mine"), migrated.palettes.map { it.name })
        assertEquals(8, migrated.active.filledCount)
    }

    @Test
    fun `an empty pref file yields the default palette`() {
        assertEquals(RadialPalette.default(), migratedPaletteSet(null, null, 0).active)
    }

    @Test
    fun `a saved list wins over the legacy pref`() {
        val list = encodeRadialPalettes(listOf(RadialPalette(name = "A"), RadialPalette(name = "B")))
        val legacy = encodeRadialPalette(RadialPalette(name = "Old"))
        val migrated = migratedPaletteSet(list, legacy, activeIndex = 1)
        assertEquals(listOf("A", "B"), migrated.palettes.map { it.name })
        assertEquals("B", migrated.active.name)
    }
}
