package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers the "Save As … / Attach" rewrite: switching a PDF-backed document to `domain="attach"`
 * touches only the first PDF page (the one carrying the reference) and points it at the bundled
 * sibling `bg.pdf`, matching desktop Xournal++'s attach convention so the pair round-trips.
 */
class PdfBackgroundDomainTest {

    private fun pdfDoc(): Document = Document(
        pages = listOf(
            Page(595.0, 842.0, Background.Pdf(filename = "content://notes.pdf", pageNo = 0, domain = "absolute"), listOf(Layer(emptyList()))),
            Page(595.0, 842.0, Background.Pdf(filename = null, pageNo = 1, domain = null), listOf(Layer(emptyList()))),
        ),
    )

    @Test fun attachRewritesOnlyTheReferencePage() {
        val out = documentWithPdfDomain(pdfDoc(), ATTACH_DOMAIN)

        val first = out.pages[0].background as Background.Pdf
        assertEquals(ATTACH_DOMAIN, first.domain)
        assertEquals(ATTACH_PDF_FILENAME, first.filename)
        assertEquals("pageno is untouched", 0, first.pageNo)

        val second = out.pages[1].background as Background.Pdf
        assertNull("later PDF pages stay bare — no domain/filename", second.domain)
        assertNull(second.filename)
    }

    @Test fun absoluteIsIdentity() {
        val doc = pdfDoc()
        assertSame("absolute leaves the document object untouched", doc, documentWithPdfDomain(doc, ABSOLUTE_DOMAIN))
    }

    @Test fun attachIgnoresPlainDocuments() {
        val plain = Document(pages = listOf(Page(595.0, 842.0, Background.Solid(0xFFFFFFFF.toInt(), "plain"), listOf(Layer(emptyList())))))
        val out = documentWithPdfDomain(plain, ATTACH_DOMAIN)
        assertEquals("no PDF page to rewrite — unchanged", plain, out)
    }
}
