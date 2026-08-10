package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
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

    @Test fun moveShiftsAPageForward() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"), page(400.0, "d"))
        val out = PageOps.move(pages, 0, 2)
        assertEquals(4, out.size)
        assertEquals(listOf(pages[1], pages[2], pages[0], pages[3]), out)
    }

    @Test fun moveShiftsAPageBackward() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"))
        assertEquals(listOf(pages[2], pages[0], pages[1]), PageOps.move(pages, 2, 0))
    }

    @Test fun moveToSamePositionIsANoOp() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        assertSame(pages, PageOps.move(pages, 1, 1))
    }

    @Test fun moveClampsTargetAndRejectsBadSource() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"))
        assertEquals("target clamps to the last slot", listOf(pages[1], pages[2], pages[0]), PageOps.move(pages, 0, 9))
        assertSame("out-of-range source changes nothing", pages, PageOps.move(pages, 7, 0))
        assertSame(pages, PageOps.move(pages, -1, 0))
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

    @Test fun removeAllDropsEverySelectedPage() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"), page(400.0, "d"))
        val out = PageOps.removeAll(pages, setOf(0, 2))
        assertEquals(listOf(pages[1], pages[3]), out)
    }

    @Test fun removeAllIgnoresOutOfRangeIndices() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        assertEquals(listOf(pages[1]), PageOps.removeAll(pages, setOf(0, 5, -3)))
        assertSame("nothing in range changes nothing", pages, PageOps.removeAll(pages, setOf(9)))
    }

    @Test fun removeAllOnAnEmptySelectionIsANoOp() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        assertSame(pages, PageOps.removeAll(pages, emptySet()))
    }

    @Test fun removeAllRefusesToEmptyTheDocument() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        assertSame("selecting every page deletes nothing", pages, PageOps.removeAll(pages, setOf(0, 1)))
    }

    @Test fun appendPagesAddsImportedPagesAfterExistingOnes() {
        val existing = listOf(page(100.0, "graph", withStroke = true), page(100.0, "lined"))
        val added = listOf(Page(400.0, 600.0, Background.Pdf("doc.pdf", 0, "absolute"), listOf(Layer(emptyList()))))
        val out = PageOps.appendPages(existing, added)
        assertEquals(3, out.size)
        assertSame(existing[0], out[0])
        assertSame(added[0], out[2])
    }

    @Test fun appendPagesDropsTheLoneUntouchedBlankSheet() {
        // A brand-new document is one empty page; keeping it would leave a stray blank before the PDF.
        val added = listOf(Page(400.0, 600.0, Background.Pdf("doc.pdf", 0, "absolute"), listOf(Layer(emptyList()))))
        assertEquals(added, PageOps.appendPages(listOf(page(100.0, "plain")), added))
    }

    @Test fun appendPagesKeepsALoneBlankSheetThatHasContent() {
        val existing = listOf(page(100.0, "plain", withStroke = true))
        val added = listOf(Page(400.0, 600.0, Background.Pdf("doc.pdf", 0, "absolute"), listOf(Layer(emptyList()))))
        assertEquals(2, PageOps.appendPages(existing, added).size)
    }

    @Test fun appendPagesWithNothingToAddIsANoOp() {
        val existing = listOf(page(100.0, "plain"))
        assertSame(existing, PageOps.appendPages(existing, emptyList()))
    }

    @Test fun copyOfTakesTheSelectedPagesInDocumentOrder() {
        val pages = listOf(page(100.0, "a", withStroke = true), page(200.0, "b"), page(300.0, "c"))
        val copied = PageOps.copyOf(pages, setOf(2, 0))
        assertEquals(2, copied.size)
        assertSame("content comes along by reference — strokes, layers, background", pages[0], copied[0])
        assertSame(pages[2], copied[1])
    }

    @Test fun copyOfIgnoresOutOfRangeIndices() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        assertEquals(listOf(pages[1]), PageOps.copyOf(pages, setOf(1, 7, -2)))
        assertEquals(emptyList<Page>(), PageOps.copyOf(pages, emptySet()))
    }

    @Test fun insertAfterPutsTheCopiesRightBehindTheTargetPage() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"), page(300.0, "c"))
        val copied = PageOps.copyOf(pages, setOf(0, 1))
        val out = PageOps.insertAfter(pages, 1, copied)
        assertEquals(5, out.pages.size)
        assertEquals(listOf(pages[0], pages[1], pages[0], pages[1], pages[2]), out.pages)
        assertEquals(2, out.index)
    }

    @Test fun insertAfterClampsAndCanPrepend() {
        val pages = listOf(page(100.0, "a"), page(200.0, "b"))
        val added = listOf(page(300.0, "c"))
        val front = PageOps.insertAfter(pages, -1, added)
        assertSame("before the first page", added[0], front.pages[0])
        assertEquals(0, front.index)
        val back = PageOps.insertAfter(pages, 9, added)
        assertSame("past the end lands last", added[0], back.pages.last())
        assertEquals("index of the first added page", 2, back.index)
    }

    @Test fun insertAfterWithNothingToInsertIsANoOp() {
        val pages = listOf(page(100.0, "a"))
        assertSame(pages, PageOps.insertAfter(pages, 0, emptyList()).pages)
    }

    @Test fun addBeforeInsertsBlankPageAheadOfTheIndex() {
        val pages = listOf(page(100.0, "graph", withStroke = true), page(300.0, "lined"))
        val out = PageOps.addBefore(pages, 1)
        assertEquals(3, out.size)
        assertSame("originals kept by reference", pages[0], out[0])
        assertSame("the indexed page moves down one", pages[1], out[2])
        val fresh = out[1]
        assertEquals("inherits width from the page it precedes", 300.0, fresh.width, 1e-9)
        assertEquals("inherits solid ruling", "lined", (fresh.background as Background.Solid).style)
        assertTrue("new page is empty", fresh.layers.single().elements.isEmpty())
    }

    @Test fun addBeforeAtZeroPrependsAndEmptyInputIsUnchanged() {
        val pages = listOf(page(100.0, "plain"))
        assertEquals(2, PageOps.addBefore(pages, 0).size)
        assertSame(pages[0], PageOps.addBefore(pages, 0)[1])
        assertTrue(PageOps.addBefore(emptyList(), 0).isEmpty())
    }

    @Test fun duplicateAtCopiesContentAndBackgroundStraightAfterItself() {
        val pdf = Page(400.0, 600.0, Background.Pdf(filename = "doc.pdf", pageNo = 3, domain = "absolute"),
            listOf(Layer(listOf(Stroke(Tool.PEN, 0, "round", listOf(StrokePoint(1.0, 1.0, 1.0), StrokePoint(2.0, 2.0, 1.0)), uniformWidth = false)))))
        val out = PageOps.duplicateAt(listOf(page(100.0, "plain"), pdf), 1)
        assertEquals(3, out.size)
        val copy = out[2]
        assertEquals("keeps the strokes", pdf.layers, copy.layers)
        assertEquals("keeps the PDF background verbatim", pdf.background, copy.background)
        assertEquals("keeps the page size", 600.0, copy.height, 1e-9)
    }

    @Test fun duplicateAtClampsTheIndexAndLeavesEmptyInputAlone() {
        val pages = listOf(page(100.0, "plain"))
        assertEquals(2, PageOps.duplicateAt(pages, 99).size)
        assertTrue(PageOps.duplicateAt(emptyList(), 0).isEmpty())
    }
}
