package com.nexopp.format

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in that a PDF-annotation document — the shape "Import PDF" builds (see `PdfImport`) — writes
 * and reads back unchanged: the first page carries `filename`+`domain`, later pages reference the
 * same PDF by `pageno` alone. This is the on-disk contract that lets an annotated PDF round-trip to
 * desktop Xournal++; the rasterisation itself is Android-only and exercised on the emulator.
 */
class PdfBackgroundRoundTripTest {

    private fun pdfDoc(): Document = Document(
        pages = listOf(
            Page(595.0, 842.0, Background.Pdf(filename = "notes.pdf", pageNo = 0, domain = "absolute"), listOf(Layer(emptyList()))),
            Page(595.0, 842.0, Background.Pdf(filename = null, pageNo = 1, domain = null), listOf(Layer(emptyList()))),
        ),
    )

    @Test fun pdfBackgroundSurvivesReserialize() {
        val doc = pdfDoc()
        assertEquals(doc, Xopp.parseXml(Xopp.toXml(doc)))
    }

    @Test fun firstPageKeepsFilenameLaterPagesJustPageno() {
        val doc = Xopp.parseXml(Xopp.toXml(pdfDoc()))

        val first = doc.pages[0].background as Background.Pdf
        assertEquals("notes.pdf", first.filename)
        assertEquals("absolute", first.domain)
        assertEquals(0, first.pageNo)

        val second = doc.pages[1].background as Background.Pdf
        assertNull("later pages inherit the PDF; only pageno is written", second.filename)
        assertNull(second.domain)
        assertEquals(1, second.pageNo)
    }

    /**
     * The on-disk `pageno` must be 1-based to match desktop Xournal++ (SaveHandler writes
     * `getPdfPageNr() + 1`), even though we index pages 0-based internally. Locks in the
     * cross-platform convention so a file saved here opens on the same PDF page on desktop.
     */
    @Test fun onDiskPagenoIsOneBased() {
        val xml = Xopp.toXml(pdfDoc()) // pages hold pageNo 0 and 1 internally
        assertTrue("first PDF page (internal 0) writes pageno=\"1\"", xml.contains("pageno=\"1\""))
        assertTrue("second PDF page (internal 1) writes pageno=\"2\"", xml.contains("pageno=\"2\""))
        assertFalse("no 0-based pageno should be emitted", xml.contains("pageno=\"0\""))
    }

    /**
     * Desktop Xournal++ has no `relative` domain: a relative path rides under `domain="absolute"`
     * and is resolved against the `.xopp`'s own folder. The parser must therefore carry the string
     * through untouched — normalising it here would break the reference on the desktop side.
     */
    @Test fun relativeReferenceSurvivesUnderAbsoluteDomain() {
        val doc = Document(
            pages = listOf(
                Page(595.0, 842.0, Background.Pdf("scans/bg.pdf", 0, "absolute"), listOf(Layer(emptyList()))),
            ),
        )
        val xml = Xopp.toXml(doc)
        assertTrue(xml.contains("filename=\"scans/bg.pdf\""))
        assertTrue(xml.contains("domain=\"absolute\""))
        assertEquals(doc, Xopp.parseXml(xml))
    }

    /**
     * A non-zip `domain="attach"` reference names the `<xoppname>.<filename>` sibling. It has to
     * round-trip verbatim so a document opened with an unreachable attachment is still saved with
     * its reference intact rather than silently losing the background.
     */
    @Test fun attachReferenceSurvivesOnANonZipDocument() {
        val doc = Document(
            pages = listOf(
                Page(595.0, 842.0, Background.Pdf("bg.pdf", 0, "attach"), listOf(Layer(emptyList()))),
            ),
        )
        val parsed = Xopp.parseXml(Xopp.toXml(doc))
        val bg = parsed.pages[0].background as Background.Pdf
        assertEquals("attach", bg.domain)
        assertEquals("bg.pdf", bg.filename)
    }
}
