package com.nexopp.format.rnote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers here come from `fixtures/rnote/backgrounds.rnote` and its `.xopp` twin: an A4 canvas
 * of `793.701 x 1122.52` px whose five pages landed at y = 40.0, 1162.5, 2285.0, 3407.6, 4530.1.
 */
class RnotePaginationTest {

    private val a4 = RnoteFormat(width = 793.701, height = 1122.52, dpi = 96.0, orientation = "portrait")

    /** The last stroke's bottom edge in the `backgrounds.rnote` fixture. */
    private val fixtureBounds = RnoteBounds(minX = 40.0, minY = 40.0, maxX = 700.0, maxY = 4570.1)

    @Test
    fun `an A4 canvas page is A4 in points`() {
        val layout = pageLayout(a4, fixtureBounds)
        assertEquals(595.28, layout.pageWidth, 0.01)
        assertEquals(841.89, layout.pageHeight, 0.01)
    }

    @Test
    fun `the fixture's content spans five pages`() {
        assertEquals(5, pageLayout(a4, fixtureBounds).pageCount)
    }

    @Test
    fun `an empty canvas still gets one page and no shift`() {
        val layout = pageLayout(a4, null)
        assertEquals(1, layout.pageCount)
        assertEquals(0.0, layout.shiftX, 1e-9)
        assertEquals(0.0, layout.shiftY, 1e-9)
    }

    @Test
    fun `each fixture page's strokes land on their own page`() {
        val layout = pageLayout(a4, fixtureBounds)
        val tops = listOf(40.0, 1162.5, 2285.0, 3407.6, 4530.1)
        tops.forEachIndexed { expected, minY ->
            val bounds = RnoteBounds(minX = 40.0, minY = minY, maxX = 700.0, maxY = minY + 40.0)
            assertEquals(expected, pageIndexFor(bounds, layout, a4))
        }
    }

    @Test
    fun `content above the origin is shifted down onto the first page`() {
        val bounds = RnoteBounds(minX = 40.0, minY = -100.0, maxX = 700.0, maxY = 500.0)
        val layout = pageLayout(a4, bounds)
        assertEquals(100.0, layout.shiftY, 1e-9)
        assertEquals(0.0, layout.shiftX, 1e-9)
        assertEquals(0, pageIndexFor(bounds, layout, a4))
    }

    @Test
    fun `content left of the origin is shifted right`() {
        val bounds = RnoteBounds(minX = -250.0, minY = 40.0, maxX = 700.0, maxY = 500.0)
        assertEquals(250.0, pageLayout(a4, bounds).shiftX, 1e-9)
    }

    @Test
    fun `a stroke straddling a page boundary stays on the page its top edge is in`() {
        val layout = pageLayout(a4, fixtureBounds)
        val straddler = RnoteBounds(minX = 40.0, minY = 1100.0, maxX = 700.0, maxY = 1200.0)
        assertEquals(0, pageIndexFor(straddler, layout, a4))
    }

    @Test
    fun `the page origin folds the shift in and steps by a page height`() {
        val bounds = RnoteBounds(minX = -250.0, minY = -100.0, maxX = 700.0, maxY = 4570.1)
        val layout = pageLayout(a4, bounds)
        val (x0, y0) = pageOriginPx(0, layout, a4)
        assertEquals(250.0, x0, 1e-9)
        assertEquals(100.0, y0, 1e-9)
        val (x2, y2) = pageOriginPx(2, layout, a4)
        assertEquals(250.0, x2, 1e-9)
        assertEquals(100.0 - 2 * 1122.52, y2, 1e-9)
    }
}
