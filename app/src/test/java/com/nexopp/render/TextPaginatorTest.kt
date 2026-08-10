package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wrapping/pagination is pure, so measurement is faked: every character is 10pt wide. */
class TextPaginatorTest {

    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun `preserves existing newlines including empty lines`() {
        assertEquals(listOf("a", "", "b"), TextPaginator.wrap("a\n\nb", 1000.0, measure))
    }

    @Test
    fun `breaks on whitespace at the width limit`() {
        // 5 chars per line at 50pt.
        assertEquals(listOf("aa bb", "cc dd"), TextPaginator.wrap("aa bb cc dd", 50.0, measure))
    }

    @Test
    fun `hard-breaks a run with no spaces`() {
        assertEquals(listOf("abcde", "fghij", "kl"), TextPaginator.wrap("abcdefghijkl", 50.0, measure))
    }

    @Test
    fun `hard-breaks an over-long word after flushing the current line`() {
        assertEquals(
            listOf("ab", "cdefg", "hijkl", "m"),
            TextPaginator.wrap("ab cdefghijklm", 50.0, measure),
        )
    }

    @Test
    fun `expands tabs to the next tab stop`() {
        assertEquals("    x", TextPaginator.expandTabs("\tx"))
        assertEquals("ab  c", TextPaginator.expandTabs("ab\tc"))
        assertEquals("abcd    e", TextPaginator.expandTabs("abcd\te"))
    }

    @Test
    fun `tabs are expanded before wrapping`() {
        assertEquals(listOf("    x"), TextPaginator.wrap("\tx", 50.0, measure))
    }

    @Test
    fun `page size is A4 and holds a whole number of lines`() {
        val spec = TextPaginator.PageSpec()
        assertEquals(DrawingSurfaceDefaults.A4_WIDTH_PT, spec.widthPt, 1e-9)
        assertEquals(DrawingSurfaceDefaults.A4_HEIGHT_PT, spec.heightPt, 1e-9)
        // 841.89 - 144 = 697.89pt of content at 12pt per line.
        assertEquals(58, spec.linesPerPage)
    }

    @Test
    fun `paginate fills a page before starting the next`() {
        val spec = TextPaginator.PageSpec(heightPt = 144.0 + 36.0) // room for exactly 3 lines
        assertEquals(3, spec.linesPerPage)
        val pages = TextPaginator.paginate((1..7).map { "line $it" }, spec)
        assertEquals(3, pages.size)
        assertEquals(listOf("line 1", "line 2", "line 3"), pages[0])
        assertEquals(listOf("line 7"), pages[2])
    }

    @Test
    fun `empty text still yields one page`() {
        assertEquals(1, TextPaginator.layout("", measure = measure).size)
    }

    @Test
    fun `layout wraps to the content width then paginates`() {
        val spec = TextPaginator.PageSpec(widthPt = 172.0, heightPt = 168.0, marginPt = 36.0)
        assertEquals(10, (spec.contentWidthPt / 10).toInt()) // 10 chars per line
        assertEquals(8, spec.linesPerPage)
        val pages = TextPaginator.layout("x".repeat(200), spec, measure)
        assertEquals(20, pages.sumOf { it.size })
        assertEquals(3, pages.size)
        assertTrue(pages.all { page -> page.all { it.length <= 10 } })
    }

    @Test
    fun `streaming layout matches the list-based layout`() {
        val spec = TextPaginator.PageSpec(widthPt = 172.0, heightPt = 168.0, marginPt = 36.0)
        val text = "hello world\n\n" + "x".repeat(200) + "\nlast line"
        val streamed = TextPaginator.layout(text.splitToSequence("\n"), spec, measure).toList()
        assertEquals(TextPaginator.layout(text, spec, measure), streamed)
    }

    @Test
    fun `streaming layout emits a page before consuming the whole input`() {
        val spec = TextPaginator.PageSpec(heightPt = 144.0 + 36.0) // room for exactly 3 lines
        var pulled = 0
        val source = generateSequence { "line ${++pulled}" }.take(1000)
        val firstPage = TextPaginator.layout(source, spec, measure).first()
        assertEquals(listOf("line 1", "line 2", "line 3"), firstPage)
        assertTrue("pulled $pulled source lines for one page", pulled <= 4)
    }

    @Test
    fun `streaming layout reads from a Reader and still yields one page when empty`() {
        val spec = TextPaginator.PageSpec(heightPt = 144.0 + 36.0)
        val pages = TextPaginator.layout(java.io.StringReader("a\nb\nc\nd"), spec, measure).toList()
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d")), pages)
        assertEquals(listOf(emptyList<String>()), TextPaginator.layout(java.io.StringReader(""), spec, measure).toList())
    }

    @Test
    fun `baselines advance by one line height below the top margin`() {
        val spec = TextPaginator.PageSpec()
        assertEquals(72.0 + 12.0, TextPaginator.baselineFromTop(0, spec), 1e-9)
        assertEquals(72.0 + 24.0, TextPaginator.baselineFromTop(1, spec), 1e-9)
    }
}
