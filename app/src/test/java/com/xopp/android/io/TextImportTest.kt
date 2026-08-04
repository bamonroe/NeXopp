package com.xopp.android.io

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.xopp.android.render.AssetFonts
import com.xopp.android.render.TextPdfGenerator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the caching contract a text import leans on: typesetting is expensive, so the same text
 * under the same name must come back as the very same store file, while anything that changes the
 * content — or a prune that swept the file — must produce a fresh one.
 */
class TextImportTest {

    @get:Rule val tmp = TemporaryFolder()

    private var generations = 0

    private fun generator() = TextPdfGenerator(AssetFonts.loader { generations++ })

    private fun source(text: String): File = tmp.newFile().apply { writeText(text) }

    @Test fun generatesAPdfWithTheSourceTextSelectable() {
        val store = PdfStore(tmp.newFolder())
        val pdf = TextImport(store, generator()).pdfFor(source("hello text import"), "notes.txt")

        assertTrue("a real PDF file was written", pdf.isFile && pdf.length() > 0)
        val stripped = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("text layer holds the source: $stripped", stripped.contains("hello text import"))
    }

    @Test fun sameTextAndNameReuseTheGeneratedPdf() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        val first = import.pdfFor(source("some notes"), "notes.txt")
        generations = 0
        val second = import.pdfFor(source("some notes"), "notes.txt")

        assertEquals(first, second)
        assertEquals("nothing was typeset a second time", 0, generations)
    }

    @Test fun differentTextOrNameGetsItsOwnPdf() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        val base = import.pdfFor(source("some notes"), "notes.txt")

        assertNotEquals(base, import.pdfFor(source("other notes"), "notes.txt"))
        assertNotEquals(base, import.pdfFor(source("some notes"), "other.txt"))
    }

    @Test fun theSameBytesImportedAsMarkdownDoNotReuseThePlainTextPdf() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        val plain = import.pdfFor(source("# heading"), "notes.txt")
        generations = 0
        val markdown = import.pdfFor(source("# heading"), "notes.md")

        assertNotEquals("markdown is cached under its own key", plain, markdown)
        assertEquals("and was typeset in its own right", 1, generations)
    }

    @Test fun markdownIsRoutedByNameNotByContent() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        val md = import.pdfFor(source("# heading"), "notes.md")
        generations = 0

        assertEquals("the suffix alone decides, and it caches", md, import.pdfFor(source("# heading"), "notes.md"))
        assertEquals(0, generations)
        assertNotEquals("a .markdown suffix routes the same way a .md does", md, import.pdfFor(source("# heading"), "notes.markdown"))
    }

    @Test fun pruningTheStoreForcesRegeneration() {
        val store = PdfStore(tmp.newFolder())
        val import = TextImport(store, generator())
        val first = import.pdfFor(source("some notes"), "notes.txt")

        // The tab closed and the cache budget is exhausted, so the entry is evicted.
        store.prune(emptyList(), maxBytes = 0)
        generations = 0
        val second = import.pdfFor(source("some notes"), "notes.txt")

        assertNotEquals("the swept file is not handed back", first, second)
        assertEquals("it was typeset again", 1, generations)
        assertTrue(second.isFile)
    }

    @Test fun aFileOverTheCapIsRefusedWithoutTypesettingIt() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        val big = source("x".repeat(4096))
        generations = 0

        val failure = runCatching { import.pdfFor(big, "huge.log", maxBytes = 1024) }.exceptionOrNull()

        assertTrue("refused as too large: $failure", failure is TextTooLargeException)
        assertTrue("the message names the limit", failure!!.message!!.contains("limit"))
        assertEquals("nothing was typeset", 0, generations)
    }

    @Test fun aFileUnderTheCapImportsNormally() {
        val import = TextImport(PdfStore(tmp.newFolder()), generator())
        assertTrue(import.pdfFor(source("small note"), "notes.txt", maxBytes = 1024).isFile)
    }

    @Test fun aLivePdfSurvivesPruning() {
        val store = PdfStore(tmp.newFolder())
        val import = TextImport(store, generator())
        val pdf = import.pdfFor(source("some notes"), "notes.txt")

        store.prune(listOf(pdf.absolutePath))
        generations = 0

        assertEquals(pdf, import.pdfFor(source("some notes"), "notes.txt"))
        assertEquals(0, generations)
    }
}
