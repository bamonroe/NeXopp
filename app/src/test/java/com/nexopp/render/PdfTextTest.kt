package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for PDF text-layer grouping and range selection (no PDFBox/Android needed). */
class PdfTextTest {

    private fun ch(c: Char, x: Double) = CharBox(c, x, 0.0, x + 10, 12.0)

    @Test fun groupsGlyphsIntoWhitespaceDelimitedWords() {
        // "hi  yo" — two words, double space collapsed, boxes unioned.
        val chars = listOf(ch('h', 0.0), ch('i', 10.0), ch(' ', 20.0), ch(' ', 30.0), ch('y', 40.0), ch('o', 50.0))
        val words = PdfWordGrouper.group(chars)
        assertEquals(listOf("hi", "yo"), words.map { it.text })
        assertEquals(0.0, words[0].left, 1e-9)
        assertEquals(20.0, words[0].right, 1e-9)   // hi spans 0..20
        assertEquals(40.0, words[1].left, 1e-9)
    }

    @Test fun groupBreaksOnAWideGapEvenWithoutASpaceGlyph() {
        // "ab" then a 10pt gap (> 0.35*height) then "cd", no space character between them.
        val chars = listOf(ch('a', 0.0), ch('b', 10.0), ch('c', 30.0), ch('d', 40.0))
        assertEquals(listOf("ab", "cd"), PdfWordGrouper.group(chars).map { it.text })
    }

    @Test fun groupBreaksOnALineChange() {
        // Same x, but the second glyph drops a full line down → separate words.
        val a = CharBox('a', 0.0, 0.0, 10.0, 12.0)
        val b = CharBox('b', 0.0, 20.0, 10.0, 32.0)
        assertEquals(listOf("a", "b"), PdfWordGrouper.group(listOf(a, b)).map { it.text })
    }

    @Test fun groupIgnoresLeadingAndTrailingWhitespace() {
        val chars = listOf(ch(' ', 0.0), ch('a', 10.0), ch(' ', 20.0))
        assertEquals(listOf("a"), PdfWordGrouper.group(chars).map { it.text })
    }

    private fun word(text: String, left: Double) = PdfWord(text, left, 0.0, left + 20, 12.0)

    private val index = PdfTextIndex(
        listOf(listOf(word("The", 0.0), word("quick", 30.0), word("brown", 70.0), word("fox", 110.0))),
    )

    @Test fun anchorPicksTheWordUnderThePoint() {
        assertEquals(1, index.anchorWord(0, 35.0, 6.0))  // inside "quick"
    }

    @Test fun anchorFallsBackToNearestWhenBetweenWords() {
        // A point below the line, closest to "brown" horizontally.
        assertEquals(2, index.anchorWord(0, 80.0, 200.0))
    }

    @Test fun anchorIsNullOnAPageWithoutText() {
        assertNull(index.anchorWord(5, 0.0, 0.0))
    }

    @Test fun rangeSelectsInclusiveReadingOrderEitherDirection() {
        assertEquals("quick brown", index.rangeText(0, 1, 2))
        assertEquals("quick brown", index.rangeText(0, 2, 1)) // order-independent
        assertEquals(2, index.rangeBoxes(0, 1, 2).size)
    }

    @Test fun rangeClampsOutOfBoundsIndices() {
        assertEquals("The quick brown fox", index.rangeText(0, -5, 99))
    }

    @Test fun hasAnyTextReflectsWhetherAnyPageHadText() {
        assertTrue(index.hasAnyText)
        assertFalse(PdfTextIndex(listOf(emptyList(), emptyList())).hasAnyText)
    }
}
