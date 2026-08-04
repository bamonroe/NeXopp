package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.OutputStream

/**
 * Typesets a plain-text file into a real PDF with **selectable, embedded text** — the background a
 * text import is opened against (see [PdfImport] and [TextPaginator]). Nothing is rasterised and no
 * OCR is involved: each laid-out line is drawn with `showText` using a bundled `PDType0Font`, so
 * [PdfTextExtractor] recovers the original characters and the reader can select and search them.
 *
 * Layout is delegated wholly to [TextPaginator] — this class only authors the PDF. The font is
 * injected as a `(PDDocument) -> PdfFonts.Embedded` loader so the generator stays free of Android's
 * `AssetManager` and is unit-testable on the JVM; the app passes `{ fonts.load(it, MONOSPACE) }`,
 * monospace being the sensible default for logs and source.
 */
class TextPdfGenerator(private val loadFont: (PDDocument) -> PdfFonts.Embedded) {

    /**
     * Write [text] to [out] as a PDF laid out to [spec], typeset for [flavor]. Returns the page
     * count written (always at least one, so an empty file still yields a blank sheet to annotate).
     * The stream is not closed.
     */
    fun generate(
        text: String,
        out: OutputStream,
        spec: TextPaginator.PageSpec = TextPaginator.PageSpec(),
        flavor: TextFlavor = TextFlavor.PLAIN,
    ): Int {
        val doc = PDDocument()
        try {
            val font = loadFont(doc)
            // The markdown branch is the seam the parser/layout work lands in; until it exists,
            // markdown is typeset exactly as its source text, which is a correct (if plain) result.
            val source = when (flavor) {
                TextFlavor.PLAIN, TextFlavor.MARKDOWN -> text
            }
            val pages = TextPaginator.layout(source, spec, font.measurer(spec.fontSizePt))
            pages.forEach { lines -> writePage(doc, font, lines, spec) }
            doc.save(out)
            return pages.size
        } finally {
            doc.close()
        }
    }

    /** One sheet: white background, then a baseline per laid-out line, top-down. */
    private fun writePage(
        doc: PDDocument,
        font: PdfFonts.Embedded,
        lines: List<String>,
        spec: TextPaginator.PageSpec,
    ) {
        val page = PDPage(PDRectangle(spec.widthPt.toFloat(), spec.heightPt.toFloat()))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.setNonStrokingColor(255, 255, 255)
            cs.addRect(0f, 0f, spec.widthPt.toFloat(), spec.heightPt.toFloat())
            cs.fill()
            cs.setNonStrokingColor(0, 0, 0)
            lines.forEachIndexed { i, line ->
                if (line.isBlank()) return@forEachIndexed
                // PDF's origin is bottom-left; the paginator measures baselines down from the top.
                val y = spec.heightPt - TextPaginator.baselineFromTop(i, spec)
                cs.beginText()
                cs.setFont(font.font, spec.fontSizePt.toFloat())
                cs.newLineAtOffset(spec.marginPt.toFloat(), y.toFloat())
                cs.showText(font.sanitize(line))
                cs.endText()
            }
        }
    }
}
