package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the page-selection arithmetic of [PdfExporter] — which pages a zero-based selection
 * actually resolves to. The PDF writing itself needs PDFBox against real Android graphics, so only
 * the pure selection step is exercised here.
 */
class PdfExportSelectionTest {

    /** Pages are told apart by their width, which is cheap to assert on. */
    private fun page(width: Double) = Page(
        width = width,
        height = 100.0,
        background = Background.Solid(0xFFFFFFFF.toInt(), "plain"),
        layers = emptyList(),
    )

    private val doc = Document(pages = listOf(page(1.0), page(2.0), page(3.0), page(4.0)))

    private val exporter = PdfExporter(pdfSource = null)

    private fun widths(pages: List<Int>?) = exporter.selectedPages(doc, pages).map { it.width }

    @Test fun nullSelectionKeepsTheWholeDocument() {
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), widths(null))
    }

    @Test fun aSubsetKeepsOnlyThosePages() {
        assertEquals(listOf(1.0, 3.0), widths(listOf(0, 2)))
    }

    @Test fun theSelectionOrderIsHonoured() {
        assertEquals(listOf(4.0, 2.0), widths(listOf(3, 1)))
    }

    @Test fun outOfRangeIndicesAreDroppedNotThrown() {
        assertEquals(listOf(2.0), widths(listOf(-1, 1, 4, 99)))
    }

    @Test fun anEmptySelectionYieldsNoPages() {
        assertEquals(emptyList<Double>(), widths(emptyList()))
    }
}
