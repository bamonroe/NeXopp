package com.xopp.android.io

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.xopp.android.render.GlyphSanitizer
import com.xopp.android.render.PdfFonts
import com.xopp.android.render.TextPdfGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the caching contract a text import leans on: typesetting is expensive, so the same text
 * under the same name must come back as the very same store file, while anything that changes the
 * content — or a prune that swept the file — must produce a fresh one.
 */
class TextImportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fontFile = File("src/main/assets/" + PdfFonts.Face.MONOSPACE.assetPath)

    private var generations = 0

    private fun generator() = TextPdfGenerator { doc ->
        generations++
        val font = fontFile.inputStream().use { PDType0Font.load(doc, it, true) }
        PdfFonts.Embedded(font, GlyphSanitizer { s -> runCatching { font.getStringWidth(s) }.isSuccess })
    }

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

    @Test fun pruningTheStoreForcesRegeneration() {
        val store = PdfStore(tmp.newFolder())
        val import = TextImport(store, generator())
        val first = import.pdfFor(source("some notes"), "notes.txt")

        store.prune(emptyList()) // the tab closed; nothing refers to it any more
        generations = 0
        val second = import.pdfFor(source("some notes"), "notes.txt")

        assertNotEquals("the swept file is not handed back", first, second)
        assertEquals("it was typeset again", 1, generations)
        assertTrue(second.isFile)
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
