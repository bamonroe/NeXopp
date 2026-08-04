package com.xopp.android.render.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout is verified against synthetic metrics: every face is monospaced, one character advancing by
 * exactly the font size, so widths are `size * length` and every expected break is arithmetic.
 */
class MarkdownLayoutTest {

    private val measure: SizedMeasurer = { _, sizePt, text -> (sizePt * text.length).toFloat() }

    /** A small page: 200pt of content width and 100pt of content height, body text at 10pt. */
    private val style = MarkdownStyle(
        widthPt = 240.0,
        heightPt = 140.0,
        marginPt = 20.0,
        bodyFontSizePt = 10.0,
        headingSizesPt = listOf(20.0, 16.0, 12.0, 10.0, 10.0, 10.0),
    )

    private fun layout(source: String, style: MarkdownStyle = this.style) =
        MarkdownLayout.layout(source, style, measure)

    private fun lines(pages: List<MarkdownPage>) =
        pages.map { page -> page.items.mapNotNull { it.item as? MarkdownItem.Line } }

    private fun text(line: MarkdownItem.Line) = line.fragments.joinToString("") { it.text }

    @Test
    fun `empty source is one empty page`() {
        assertEquals(listOf(MarkdownPage(emptyList())), layout(""))
    }

    @Test
    fun `heading is set bold at its level's size`() {
        val page = lines(layout("# Big\n\n## Less big")).single()
        assertEquals(listOf("Big", "Less big"), page.map { text(it) })
        assertEquals(20.0, page[0].fontSizePt, 0.0)
        assertEquals(16.0, page[1].fontSizePt, 0.0)
        assertTrue(page.all { it.fragments.all { f -> f.style == RunStyle.BOLD } })
    }

    @Test
    fun `paragraph wraps to the content width and keeps inline styles`() {
        // 20 chars at 10pt is exactly the 200pt content width, so the third word wraps.
        val page = lines(layout("aaaaaaaaaa **bbbbbbbbb** cccc")).single()
        assertEquals(listOf("aaaaaaaaaa bbbbbbbbb", "cccc"), page.map { text(it) })
        assertEquals(RunStyle.BOLD, page[0].fragments[1].style)
    }

    @Test
    fun `blocks are separated by their collapsed gap`() {
        val page = layout("para\n\nmore").single()
        val baselines = page.items.map { it.yPt }
        val gap = baselines[1] - baselines[0]
        // One line height plus one block gap — the space collapses to a single gap, not two.
        assertEquals(style.bodyLineHeightPt + style.bodyLineHeightPt * style.blockSpacingRatio, gap, 1e-9)
    }

    @Test
    fun `list items indent and hang their markers`() {
        val page = lines(layout("- one\n- two")).single()
        assertEquals(listOf("one", "two"), page.map { text(it) })
        assertTrue(page.all { it.indentPt == style.listIndentPt })
        assertEquals(listOf("•", "•"), page.map { it.marker })
        assertEquals(-style.listIndentPt, page[0].markerXPt, 0.0)
    }

    @Test
    fun `ordered lists number from the list's start`() {
        val page = lines(layout("3. three\n4. four")).single()
        assertEquals(listOf("3.", "4."), page.map { it.marker })
    }

    @Test
    fun `nested structure indents by one step per level`() {
        val page = lines(layout("> - quoted item")).single()
        assertEquals(style.quoteIndentPt + style.listIndentPt, page.single().indentPt, 0.0)
    }

    @Test
    fun `code blocks are monospaced at their own size and indent`() {
        val page = lines(layout("```\nx = 1\n```")).single()
        val line = page.single()
        assertEquals("x = 1", text(line))
        assertEquals(style.codeFontSizePt, line.fontSizePt, 0.0)
        assertEquals(style.codeIndentPt, line.indentPt, 0.0)
        assertEquals(RunStyle.CODE, line.fragments.single().style)
        assertNull(line.marker)
    }

    @Test
    fun `code keeps its own spacing rather than reflowing on words`() {
        val page = lines(layout("```\n  indented\n\n  after blank\n```")).single()
        assertEquals(listOf("  indented", "", "  after blank"), page.map { text(it) })
    }

    @Test
    fun `a rule spans the content width and is centred in its box`() {
        val page = layout("a\n\n---\n\nb")
        val rule = page.single().items.first { it.item is MarkdownItem.Rule }
        val item = rule.item as MarkdownItem.Rule
        assertEquals(style.contentWidthPt, item.widthPt, 0.0)
        assertEquals(style.ruleThicknessPt, item.thicknessPt, 0.0)
    }

    @Test
    fun `a long block flows onto the next page`() {
        val pages = lines(layout(List(20) { "word$it" }.joinToString("\n\n")))
        assertTrue(pages.size > 1)
        assertEquals(20, pages.sumOf { it.size })
        // Every baseline stays inside the printable area of its page.
        assertTrue(layout(List(20) { "word$it" }.joinToString("\n\n")).all { page ->
            page.items.all { it.yPt <= style.heightPt - style.marginPt + 1e-9 }
        })
    }

    @Test
    fun `a page never opens with block spacing`() {
        val pages = layout(List(20) { "word$it" }.joinToString("\n\n"))
        for (page in pages.drop(1)) {
            assertEquals(style.marginPt + style.bodyLineHeightPt, page.items.first().yPt, 1e-9)
        }
    }

    @Test
    fun `a heading is not orphaned at the page foot`() {
        // Fill the page so only about one line of room is left, then a heading with a paragraph.
        val filler = List(7) { "line$it" }.joinToString("\n\n")
        val pages = lines(layout("$filler\n\n# Heading\n\nbody"))
        val heading = pages.indexOfFirst { page -> page.any { text(it) == "Heading" } }
        val body = pages.indexOfFirst { page -> page.any { text(it) == "body" } }
        assertTrue("heading should not sit alone on a page", heading == body)
        assertTrue("heading should move off the crowded first page", heading > 0)
    }
}
