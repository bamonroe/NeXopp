package com.nexopp.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.nexopp.render.markdown.MarkdownLayout
import com.nexopp.render.markdown.MarkdownStyle
import java.io.File
import java.io.OutputStream

/**
 * Typesets a plain-text file into a real PDF with **selectable, embedded text** — the background a
 * text import is opened against (see [PdfImport] and [TextPaginator]). Nothing is rasterised and no
 * OCR is involved: each laid-out line is drawn with `showText` using a bundled `PDType0Font`, so
 * [PdfTextExtractor] recovers the original characters and the reader can select and search them.
 *
 * Layout is delegated wholly to [TextPaginator] (plain) or [MarkdownLayout] (markdown) — this class
 * only picks the flavour and authors the PDF, handing the markdown half to [MarkdownPdfWriter].
 * Fonts are injected as a `(PDDocument, Face) -> PdfFonts.Embedded` loader so the generator stays
 * free of Android's `AssetManager` and is unit-testable on the JVM; [plainFace] is the single face
 * the plain path draws in, monospace being the sensible default for logs and source.
 */
class TextPdfGenerator(
    private val loadFont: (PDDocument, PdfFonts.Face) -> PdfFonts.Embedded,
    private val plainFace: PdfFonts.Face = PdfFonts.Face.MONOSPACE,
) {

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
    ): Int = write(out) { doc ->
        when (flavor) {
            TextFlavor.PLAIN -> writePlain(doc, text.splitToSequence("\n"), spec)
            TextFlavor.MARKDOWN -> writeMarkdown(doc, text, spec)
        }
    }

    /**
     * Streaming counterpart: typeset the text in [source] without ever holding the file in memory as
     * a `String`. The plain path reads and lays out a line at a time; markdown still needs the whole
     * source, since its block parse is not incremental.
     */
    fun generate(
        source: File,
        out: OutputStream,
        spec: TextPaginator.PageSpec = TextPaginator.PageSpec(),
        flavor: TextFlavor = TextFlavor.PLAIN,
    ): Int = write(out) { doc ->
        when (flavor) {
            TextFlavor.PLAIN -> source.bufferedReader(Charsets.UTF_8).use {
                writePlain(doc, it.lineSequence(), spec)
            }
            TextFlavor.MARKDOWN -> writeMarkdown(doc, source.readText(Charsets.UTF_8), spec)
        }
    }

    /**
     * Author one document with [fill] and save it to [out].
     *
     * It is one `PDDocument` on purpose. Authoring in bounded chunks and concatenating them was
     * measured and is **worse**: PDFBox 2's merge rebuilds the entire joined document in memory
     * before writing (`legacyMergeDocuments`), which costs more than simply holding every page in
     * the first place — a 16 MiB source peaked at 405 MiB chunked against 319 MiB unchunked. The
     * document itself is therefore the memory ceiling here, and the text-import cap in
     * [com.nexopp.ui.AppSettings] is what keeps it in bounds.
     */
    private fun write(out: OutputStream, fill: (PDDocument) -> Int): Int {
        val doc = PDDocument()
        try {
            val count = fill(doc)
            doc.save(out)
            return count
        } finally {
            doc.close()
        }
    }

    /**
     * Plain text: one face, one column of baselines per page. Layout is a lazy sequence drained page
     * by page, so neither the source text nor its wrapped lines are ever held whole.
     */
    private fun writePlain(doc: PDDocument, rawLines: Sequence<String>, spec: TextPaginator.PageSpec): Int {
        val font = loadFont(doc, plainFace)
        var pages = 0
        TextPaginator.layout(rawLines, spec, font.measurer(spec.fontSizePt)).forEach { lines ->
            writePage(doc, font, lines, spec)
            pages++
        }
        return pages
    }

    /**
     * Markdown: parsed into blocks, wrapped against the very faces it will be drawn in, then paged.
     * A source with no blocks at all still gets one blank sheet, as the plain path does.
     */
    private fun writeMarkdown(doc: PDDocument, text: String, spec: TextPaginator.PageSpec): Int {
        val writer = MarkdownPdfWriter.forDocument(doc, loadFont)
        val style = markdownStyle(spec)
        val pages = MarkdownLayout.layout(text, style, writer.measure)
        if (pages.isEmpty()) {
            writer.writeBlankPage(doc, style)
            return 1
        }
        pages.forEach { writer.writePage(doc, it, style) }
        return pages.size
    }

    /** The markdown sizes inherit the caller's page geometry and body type; the rest are defaults. */
    private fun markdownStyle(spec: TextPaginator.PageSpec) = MarkdownStyle(
        widthPt = spec.widthPt,
        heightPt = spec.heightPt,
        marginPt = spec.marginPt,
        bodyFontSizePt = spec.fontSizePt,
        lineHeightRatio = spec.lineHeightRatio,
    )

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
