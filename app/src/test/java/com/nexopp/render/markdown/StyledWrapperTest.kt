package com.nexopp.render.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrapping is verified against synthetic metrics: every face is monospaced at a fixed advance, with
 * bold and code deliberately wider than regular so a style change visibly moves a break.
 */
class StyledWrapperTest {

    private val advance = mapOf(
        RunStyle.REGULAR to 10f,
        RunStyle.ITALIC to 10f,
        RunStyle.BOLD to 20f,
        RunStyle.BOLD_ITALIC to 20f,
        RunStyle.CODE to 15f,
    )

    private val measure: StyledMeasurer = { style, text -> advance.getValue(style) * text.length }

    private fun text(line: List<RunFragment>) = line.joinToString("") { it.text }

    @Test
    fun `maps run flags to faces`() {
        assertEquals(RunStyle.REGULAR, StyledRun("a").style)
        assertEquals(RunStyle.BOLD, StyledRun("a", bold = true).style)
        assertEquals(RunStyle.ITALIC, StyledRun("a", italic = true).style)
        assertEquals(RunStyle.BOLD_ITALIC, StyledRun("a", bold = true, italic = true).style)
        // Code wins: inline code is monospaced even when the surrounding span is bold.
        assertEquals(RunStyle.CODE, StyledRun("a", bold = true, code = true).style)
    }

    @Test
    fun `empty input is one empty line`() {
        assertEquals(listOf(emptyList<RunFragment>()), StyledWrapper.wrap(emptyList(), 100.0, measure))
        assertEquals(listOf(emptyList<RunFragment>()), StyledWrapper.wrap(listOf(StyledRun("")), 100.0, measure))
    }

    @Test
    fun `a short line stays on one line and merges same-style runs`() {
        val lines = StyledWrapper.wrap(listOf(StyledRun("one "), StyledRun("two")), 500.0, measure)
        assertEquals(1, lines.size)
        assertEquals("one two", text(lines[0]))
        assertEquals(1, lines[0].size)
        assertEquals(0.0, lines[0][0].xPt, 0.001)
        assertEquals(70.0, lines[0][0].widthPt, 0.001)
    }

    @Test
    fun `styles become separate fragments at their own offsets`() {
        val runs = listOf(StyledRun("ab "), StyledRun("cd", bold = true), StyledRun(" ef", code = true))
        val lines = StyledWrapper.wrap(runs, 1000.0, measure)
        assertEquals(1, lines.size)
        val (plain, bold, code) = lines[0]
        assertEquals(RunStyle.REGULAR to 0.0, plain.style to plain.xPt)
        assertEquals(30.0, plain.widthPt, 0.001)
        // The separating space takes the face of the fragment it follows, so it rides with the bold.
        assertEquals(RunStyle.BOLD to 30.0, bold.style to bold.xPt)
        assertEquals("cd ", bold.text)
        assertEquals(60.0, bold.widthPt, 0.001)
        assertEquals(RunStyle.CODE to 90.0, code.style to code.xPt)
        assertEquals("ef", code.text)
    }

    @Test
    fun `wraps on word boundaries`() {
        // Six regular chars fit; "aaa bbb" (7) does not.
        val lines = StyledWrapper.wrap(listOf(StyledRun("aaa bbb ccc")), 60.0, measure)
        assertEquals(listOf("aaa", "bbb", "ccc"), lines.map(::text))
        assertTrue(lines.all { it.first().xPt == 0.0 })
    }

    @Test
    fun `a wider face breaks the line sooner`() {
        val runs = listOf(StyledRun("aaa "), StyledRun("bbb", bold = true))
        // "aaa bbb" is 40 + 60 = 100pt in mixed faces, so it no longer fits 80pt.
        assertEquals(listOf("aaa", "bbb"), StyledWrapper.wrap(runs, 80.0, measure).map(::text))
        assertEquals(listOf("aaa bbb"), StyledWrapper.wrap(runs, 100.0, measure).map(::text))
    }

    @Test
    fun `a word spanning runs wraps as one unit`() {
        val runs = listOf(StyledRun("xx "), StyledRun("bo", bold = true), StyledRun("ld"))
        val lines = StyledWrapper.wrap(runs, 70.0, measure)
        assertEquals(listOf("xx", "bold"), lines.map(::text))
        // The second line keeps both faces, the plain tail sitting after the bold head.
        assertEquals(2, lines[1].size)
        assertEquals(40.0, lines[1][1].xPt, 0.001)
    }

    @Test
    fun `an unbreakable word is hard-broken like the plain path`() {
        val lines = StyledWrapper.wrap(listOf(StyledRun("abcdefgh")), 30.0, measure)
        assertEquals(listOf("abc", "def", "gh"), lines.map(::text))
    }

    @Test
    fun `hard break resumes on the line already in progress`() {
        val lines = StyledWrapper.wrap(listOf(StyledRun("ab cdefg")), 30.0, measure)
        assertEquals(listOf("ab", "cde", "fg"), lines.map(::text))
    }

    @Test
    fun `inline whitespace collapses to a single separator`() {
        assertEquals(listOf("a b"), StyledWrapper.wrap(listOf(StyledRun("a\nb")), 1000.0, measure).map(::text))
        assertEquals(listOf("a b"), StyledWrapper.wrap(listOf(StyledRun("a\tb")), 1000.0, measure).map(::text))
        assertEquals(listOf("a b"), StyledWrapper.wrap(listOf(StyledRun("a   b")), 1000.0, measure).map(::text))
    }
}
