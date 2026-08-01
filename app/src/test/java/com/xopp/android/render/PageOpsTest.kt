package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpsTest {

    private fun page(w: Double, style: String, withStroke: Boolean = false) = Page(
        w, 200.0, Background.Solid(0xFFFFFFFF.toInt(), style),
        if (withStroke) listOf(Layer(listOf(Stroke(Tool.PEN, 0, "round", listOf(StrokePoint(1.0, 1.0, 1.0), StrokePoint(2.0, 2.0, 1.0)), uniformWidth = false)))) else listOf(Layer(emptyList())),
    )

    @Test fun addAfterInsertsBlankPageInheritingSizeAndSolidRuling() {
        val pages = listOf(page(100.0, "graph", withStroke = true), page(300.0, "lined"))
        val out = PageOps.addAfter(pages, 0)
        assertEquals(3, out.size)
        assertSame("originals kept by reference", pages[0], out[0])
        assertSame(pages[1], out[2])
        val fresh = out[1]
        assertEquals("inherits width", 100.0, fresh.width, 1e-9)
        assertEquals("inherits solid ruling", "graph", (fresh.background as Background.Solid).style)
        assertTrue("new page is empty", fresh.layers.single().elements.isEmpty())
    }

    @Test fun addAfterDropsPdfBackgroundToPlainSheet() {
        // A PDF-backed page: the blank successor must NOT re-show the same PDF page (a "duplicate").
        val pdfPage = Page(400.0, 600.0, Background.Pdf(filename = "doc.pdf", pageNo = 3, domain = "absolute"), listOf(Layer(emptyList())))
        val fresh = PageOps.addAfter(listOf(pdfPage), 0)[1]
        assertEquals("keeps the page size", 400.0, fresh.width, 1e-9)
        val bg = fresh.background as Background.Solid
        assertEquals("PDF background becomes a plain sheet", "plain", bg.style)
        assertEquals("plain sheet is white", 0xFFFFFFFF.toInt(), bg.color)
        assertTrue("new page is empty", fresh.layers.single().elements.isEmpty())
    }

    @Test fun addAfterClampsOutOfRangeIndex() {
        val pages = listOf(page(100.0, "plain"))
        assertEquals(2, PageOps.addAfter(pages, 9).size)
    }

    @Test fun addAfterOnEmptyReturnsSameList() {
        val empty = emptyList<Page>()
        assertSame(empty, PageOps.addAfter(empty, 0))
    }

    @Test fun removeAtDropsThePage() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"))
        val out = PageOps.removeAt(pages, 1)
        assertEquals(2, out.size)
        assertSame(pages[0], out[0])
        assertSame(pages[2], out[1])
    }

    @Test fun removeAtNeverEmptiesTheDocument() {
        val only = listOf(page(100.0, "a"))
        assertSame("last page is kept", only, PageOps.removeAt(only, 0))
    }
}
