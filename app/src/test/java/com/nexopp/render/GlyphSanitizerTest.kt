package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphSanitizerTest {

    /** A font that knows ASCII plus U+FFFD — the shape of the bundled DejaVu faces, minus coverage. */
    private fun asciiFont() = GlyphSanitizer { s -> s.all { it.code in 0x20..0x7E || it == '�' } }

    @Test
    fun `encodable text passes through unchanged`() {
        assertEquals("hello world", asciiFont().sanitize("hello world"))
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", asciiFont().sanitize(""))
    }

    @Test
    fun `unsupported codepoints become the substitution glyph`() {
        assertEquals("a�b", asciiFont().sanitize("a中b"))
    }

    @Test
    fun `substitute prefers the replacement character when the font has it`() {
        assertEquals("�", asciiFont().substitute)
    }

    @Test
    fun `substitute falls back to a question mark without the replacement character`() {
        val s = GlyphSanitizer { t -> t.all { it.code in 0x20..0x7E } }
        assertEquals("?", s.substitute)
    }

    @Test
    fun `substitute falls back to a space when nothing else encodes`() {
        val s = GlyphSanitizer { t -> t == " " }
        assertEquals(" ", s.substitute)
    }

    @Test
    fun `a predicate that throws is treated as unencodable rather than propagating`() {
        val s = GlyphSanitizer { t -> if (t == "x") throw IllegalStateException("boom") else true }
        assertEquals("a�b", s.sanitize("axb"))
    }

    @Test
    fun `an astral codepoint is replaced as one unit not two surrogates`() {
        // U+1F600 is one codepoint, two chars — an unsupporting font must not emit two substitutes.
        assertEquals("�", asciiFont().sanitize("😀"))
    }

    @Test
    fun `an astral codepoint the font supports survives intact`() {
        val emoji = "😀"
        val s = GlyphSanitizer { true }
        assertEquals(emoji, s.sanitize(emoji))
    }

    @Test
    fun `each codepoint is probed only once`() {
        var probes = 0
        val s = GlyphSanitizer { probes++; true }
        s.sanitize("aaaaabbbbb")
        assertTrue("probed $probes times for 2 distinct codepoints", probes <= 2)
    }
}
