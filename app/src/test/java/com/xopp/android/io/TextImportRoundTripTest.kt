package com.xopp.android.io

import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.xopp.android.format.XoppZip
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.GlyphSanitizer
import com.xopp.android.render.PdfFonts
import com.xopp.android.render.PdfTextExtractor
import com.xopp.android.render.TextPdfGenerator
import com.xopp.android.render.documentWithPdfDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The whole text-import journey, end to end: open a `.txt` → annotate it → save → reopen.
 *
 * The generated background PDF exists only in the prunable cache, so a text import has to save as a
 * **ZIP package** with the PDF embedded (`domain="attach"`); this test is what guards that, because
 * a linked path would reopen blank once the cache was swept. The rasterisation is Android-only, so
 * what is checked here is the on-disk contract: strokes survive, and the reopened package still
 * carries a PDF whose text layer holds the original file's words.
 */
class TextImportRoundTripTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fontFile = File("src/main/assets/" + PdfFonts.Face.MONOSPACE.assetPath)

    private fun generator() = TextPdfGenerator { doc ->
        val font = fontFile.inputStream().use { PDType0Font.load(doc, it, true) }
        PdfFonts.Embedded(font, GlyphSanitizer { s -> runCatching { font.getStringWidth(s) }.isSuccess })
    }

    private val annotation = Stroke(
        tool = Tool.PEN,
        color = 0x000000ff.toInt(),
        capStyle = null,
        points = listOf(StrokePoint(10.0, 20.0, 1.4), StrokePoint(30.0, 40.0, 1.4)),
        uniformWidth = true,
    )

    /** The document shape `PdfImport` builds over a freshly generated background, plus one stroke. */
    private fun annotated() = Document(
        pages = listOf(
            Page(
                595.0, 842.0,
                Background.Pdf(filename = "generated.pdf", pageNo = 0, domain = "absolute"),
                listOf(Layer(listOf(annotation))),
            ),
        ),
    )

    @Test fun textOpensAnnotatesSavesAndReopens() {
        val source = tmp.newFile().apply { writeText("import round trip\nsecond line\n") }

        // Open: the text is typeset into the store's background PDF.
        val store = PdfStore(tmp.newFolder())
        val background = TextImport(store, generator()).pdfFor(source, "notes.txt")

        // Save: annotated, as a ZIP package carrying the generated PDF (no on-disk source to link).
        val saved = ByteArrayOutputStream().also {
            XoppZip.save(documentWithPdfDomain(annotated(), ATTACH_DOMAIN), background, it)
        }.toByteArray()

        // Reopen: the package unpacks into a fresh store copy of the same PDF.
        val reopened = XoppZip.open(ByteArrayInputStream(saved), PdfStore(tmp.newFolder())::newFile)

        val page = reopened.doc.pages.single()
        assertEquals("the annotation survived", listOf(annotation), page.layers.single().elements)
        val bg = page.background as Background.Pdf
        assertEquals(ATTACH_DOMAIN, bg.domain)
        assertNotNull("the generated PDF travelled inside the package", reopened.pdf)

        val text = PdfTextExtractor().extract(reopened.pdf!!).let { index ->
            (0 until 1).flatMap { index.words(it) }.joinToString(" ") { it.text }
        }
        assertTrue("the original text is still selectable: $text", text.contains("round"))
    }
}
