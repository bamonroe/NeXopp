package com.xopp.android.render.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [MarkdownInlineParser]: raw inline source in, styled runs out. */
class MarkdownInlineParserTest {

    private fun parse(source: String) = MarkdownInlineParser.parse(source)

    private fun plain(text: String) = StyledRun(text)

    @Test
    fun `empty source yields no runs`() {
        assertEquals(emptyList<StyledRun>(), parse(""))
    }

    @Test
    fun `unstyled text is one run`() {
        assertEquals(listOf(plain("hello world")), parse("hello world"))
    }

    @Test
    fun `single asterisks are italic`() {
        assertEquals(
            listOf(plain("a "), StyledRun("b", italic = true), plain(" c")),
            parse("a *b* c"),
        )
    }

    @Test
    fun `single underscores are italic`() {
        assertEquals(listOf(StyledRun("b", italic = true)), parse("_b_"))
    }

    @Test
    fun `double asterisks are bold`() {
        assertEquals(
            listOf(plain("a "), StyledRun("b", bold = true)),
            parse("a **b**"),
        )
    }

    @Test
    fun `double underscores are bold`() {
        assertEquals(listOf(StyledRun("b", bold = true)), parse("__b__"))
    }

    @Test
    fun `emphasis nests inside strong`() {
        assertEquals(
            listOf(
                StyledRun("bold ", bold = true),
                StyledRun("both", bold = true, italic = true),
                StyledRun(" bold", bold = true),
            ),
            parse("**bold *both* bold**"),
        )
    }

    @Test
    fun `strong nests inside emphasis`() {
        assertEquals(
            listOf(
                StyledRun("em ", italic = true),
                StyledRun("both", italic = true, bold = true),
            ),
            parse("*em **both***"),
        )
    }

    @Test
    fun `unmatched delimiters stay literal`() {
        assertEquals(listOf(plain("2 * 3 * 4")), parse("2 * 3 * 4"))
        assertEquals(listOf(plain("a * b")), parse("a * b"))
    }

    @Test
    fun `intraword underscores survive`() {
        assertEquals(listOf(plain("snake_case_name")), parse("snake_case_name"))
    }

    @Test
    fun `intraword asterisks still emphasise`() {
        assertEquals(
            listOf(plain("a"), StyledRun("b", italic = true), plain("c")),
            parse("a*b*c"),
        )
    }

    @Test
    fun `code span is literal and unstyled inside`() {
        assertEquals(
            listOf(plain("run "), StyledRun("a *b* c", code = true)),
            parse("run `a *b* c`"),
        )
    }

    @Test
    fun `double backtick span holds a backtick`() {
        assertEquals(listOf(StyledRun("a ` b", code = true)), parse("``a ` b``"))
    }

    @Test
    fun `code span strips one symmetric pad space`() {
        assertEquals(listOf(StyledRun("`", code = true)), parse("`` ` ``"))
    }

    @Test
    fun `unclosed backtick is literal`() {
        assertEquals(listOf(plain("a `b c")), parse("a `b c"))
    }

    @Test
    fun `escaped punctuation is literal`() {
        assertEquals(listOf(plain("*not em*")), parse("\\*not em\\*"))
        assertEquals(listOf(plain("a`b")), parse("a\\`b"))
    }

    @Test
    fun `backslash before a non-punctuation character is kept`() {
        assertEquals(listOf(plain("a\\b")), parse("a\\b"))
    }

    @Test
    fun `link renders its label and drops the url`() {
        assertEquals(
            listOf(plain("see the "), StyledRun("docs", italic = true), plain(" now")),
            parse("see the [*docs*](https://example.com/a_b) now"),
        )
    }

    @Test
    fun `image renders its alt text`() {
        assertEquals(listOf(plain("a diagram")), parse("![a diagram](fig.png)"))
    }

    @Test
    fun `bracket without a url stays literal`() {
        assertEquals(listOf(plain("[just brackets]")), parse("[just brackets]"))
    }

    @Test
    fun `emphasis does not leak across a link boundary`() {
        // A delimiter inside a label can only pair inside that label, so the trailing `*` is literal.
        assertEquals(listOf(plain("a *b c* d")), parse("a *b [c*](u) d"))
        // Delimiters outside the link still pair across it, as they should.
        assertEquals(listOf(StyledRun("b c d", italic = true)), parse("*b [c](u) d*"))
    }

    @Test
    fun `code span inside emphasis keeps both marks separate`() {
        assertEquals(
            listOf(StyledRun("a ", italic = true), StyledRun("b", italic = true, code = true)),
            parse("*a `b`*"),
        )
    }

    @Test
    fun `adjacent same-style runs merge`() {
        // The escape splits the scanner's text tokens; the merge pass rejoins them into one run.
        assertEquals(listOf(StyledRun("a*b", italic = true)), parse("*a\\*b*"))
    }
}
