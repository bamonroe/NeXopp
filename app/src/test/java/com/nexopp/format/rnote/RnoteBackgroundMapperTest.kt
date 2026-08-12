package com.nexopp.format.rnote

import com.nexopp.format.model.Background
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The canvas ↔ page background mapping, against the ground-truth fixtures in
 * `app/src/test/resources/fixtures/rnote/`.
 */
class RnoteBackgroundMapperTest {

    private fun background(name: String): RnoteBackground {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")
        return RnoteSnapshot.parse(wrapper.snapshot).background
    }

    @Test
    fun `the empty fixture's dotted white canvas becomes a dotted white page`() {
        val bg = background("empty")
        assertEquals("dots", bg.pattern)
        assertEquals(Background.Solid(0xFFFFFFFF.toInt(), "dotted"), toXoppBackground(bg))
    }

    @Test
    fun `the plain fixture's unpatterned canvas becomes a plain page`() {
        val bg = background("plain")
        assertEquals("none", bg.pattern)
        assertEquals("plain", toXoppBackground(bg).style)
    }

    @Test
    fun `every mapped style round-trips through the rnote pattern name`() {
        val styles = listOf("plain", "ruled", "graph", "dotted", "isograph", "isodotted")
        for (style in styles) {
            val roundTripped = toXoppBackground(toRnoteBackground(Background.Solid(0xFF102030.toInt(), style)))
            assertEquals(style, style, roundTripped.style)
            assertEquals(style, 0xFF102030.toInt(), roundTripped.color)
        }
    }

    @Test
    fun `the two xopp-only styles degrade to what rnote can draw`() {
        assertEquals("lines", toRnoteBackground(Background.Solid(0, "lined")).pattern)
        assertEquals("none", toRnoteBackground(Background.Solid(0, "staves")).pattern)
        assertEquals("none", toRnoteBackground(Background.Solid(0, "wallpaper")).pattern)
    }

    @Test
    fun `pdf and pixmap backgrounds export as a white unpatterned canvas`() {
        for (bg in listOf(Background.Pdf("a.pdf", 0, "absolute"), Background.Pixmap("absolute", "a.png"))) {
            val exported = toRnoteBackground(bg)
            assertEquals("none", exported.pattern)
            assertEquals(0xFFFFFFFF.toInt(), exported.color.toXopp())
        }
    }

    @Test
    fun `the pattern pitch matches what xournalpp renders`() {
        assertEquals(32.0, toRnoteBackground(Background.Solid(0, "ruled")).patternWidth, 1e-9)
        assertEquals(32.0, toRnoteBackground(Background.Solid(0, "lined")).patternHeight, 1e-9)
        assertEquals(18.893, toRnoteBackground(Background.Solid(0, "graph")).patternWidth, 1e-9)
        assertEquals(18.893, toRnoteBackground(Background.Solid(0, "isodotted")).patternHeight, 1e-9)
    }
}
