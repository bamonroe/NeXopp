package com.nexopp.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.nexopp.render.markdown.MarkdownItem
import com.nexopp.render.markdown.MarkdownPage
import com.nexopp.render.markdown.MarkdownStyle
import com.nexopp.render.markdown.RunStyle
import com.nexopp.render.markdown.SizedMeasurer

/**
 * Draws laid-out markdown pages into a [PDDocument] — the PDFBox half of the markdown import,
 * paired with the pure layout in `render/markdown/`.
 *
 * Two jobs, and they must agree exactly. It [measure]s text for [com.nexopp.render.markdown.MarkdownLayout]
 * and it draws what came back, both through the same [PdfFonts.Embedded] per [RunStyle] — so a
 * wrapped line is never wider on the page than the width it was wrapped to. That is the whole
 * reason the faces are resolved once, up front, into [faces].
 *
 * Unlike the plain-text path, a line here is several fragments at different x offsets in different
 * faces, so each fragment gets its own `beginText`/`showText` pair; a horizontal rule is drawn as a
 * filled rect rather than a stroke, which needs no graphics-state changes.
 */
class MarkdownPdfWriter(private val faces: Map<RunStyle, PdfFonts.Embedded>) {

    /** Measures through the same embedded faces the page is drawn with. */
    val measure: SizedMeasurer = { style, fontSizePt, text -> face(style).widthPt(text, fontSizePt) }

    /** One sheet: white background, then every placed item at its own offset. */
    fun writePage(doc: PDDocument, page: MarkdownPage, style: MarkdownStyle) {
        val pdPage = PDPage(PDRectangle(style.widthPt.toFloat(), style.heightPt.toFloat()))
        doc.addPage(pdPage)
        PDPageContentStream(doc, pdPage).use { cs ->
            cs.setNonStrokingColor(255, 255, 255)
            cs.addRect(0f, 0f, style.widthPt.toFloat(), style.heightPt.toFloat())
            cs.fill()
            cs.setNonStrokingColor(0, 0, 0)
            page.items.forEach { placed ->
                // PDF's origin is bottom-left; layout measures every offset down from the page top.
                val y = style.heightPt - placed.yPt
                when (val item = placed.item) {
                    is MarkdownItem.Line -> drawLine(cs, item, style, y)
                    is MarkdownItem.Rule -> drawRule(cs, item, style, y)
                    is MarkdownItem.Space -> Unit
                }
            }
        }
    }

    /** An empty document still gets a sheet, so there is always something to annotate. */
    fun writeBlankPage(doc: PDDocument, style: MarkdownStyle) =
        writePage(doc, MarkdownPage(emptyList()), style)

    private fun drawLine(cs: PDPageContentStream, line: MarkdownItem.Line, style: MarkdownStyle, y: Double) {
        val left = style.marginPt + line.indentPt
        line.marker?.let { drawText(cs, it, line.markerStyle, line.fontSizePt, left + line.markerXPt, y) }
        line.fragments.forEach { drawText(cs, it.text, it.style, line.fontSizePt, left + it.xPt, y) }
    }

    private fun drawText(
        cs: PDPageContentStream,
        text: String,
        style: RunStyle,
        fontSizePt: Double,
        xPt: Double,
        yPt: Double,
    ) {
        if (text.isEmpty()) return
        val embedded = face(style)
        cs.beginText()
        cs.setFont(embedded.font, fontSizePt.toFloat())
        cs.newLineAtOffset(xPt.toFloat(), yPt.toFloat())
        cs.showText(embedded.sanitize(text))
        cs.endText()
    }

    /** A rule is a filled rect centred on its placed y, matching how layout measures it. */
    private fun drawRule(cs: PDPageContentStream, rule: MarkdownItem.Rule, style: MarkdownStyle, y: Double) {
        cs.addRect(
            (style.marginPt + rule.indentPt).toFloat(),
            (y - rule.thicknessPt / 2).toFloat(),
            rule.widthPt.toFloat(),
            rule.thicknessPt.toFloat(),
        )
        cs.fill()
    }

    private fun face(style: RunStyle): PdfFonts.Embedded =
        faces[style] ?: faces.getValue(RunStyle.REGULAR)

    companion object {
        /** Which bundled face sets a given run style. Code is the one monospaced style. */
        fun faceFor(style: RunStyle): PdfFonts.Face = when (style) {
            RunStyle.REGULAR -> PdfFonts.Face.PROPORTIONAL
            RunStyle.BOLD -> PdfFonts.Face.PROPORTIONAL_BOLD
            RunStyle.ITALIC -> PdfFonts.Face.PROPORTIONAL_ITALIC
            RunStyle.BOLD_ITALIC -> PdfFonts.Face.PROPORTIONAL_BOLD_ITALIC
            RunStyle.CODE -> PdfFonts.Face.MONOSPACE
        }

        /** Embed every face markdown can need into [doc], via [load]. */
        fun forDocument(doc: PDDocument, load: (PDDocument, PdfFonts.Face) -> PdfFonts.Embedded): MarkdownPdfWriter =
            MarkdownPdfWriter(RunStyle.entries.associateWith { load(doc, faceFor(it)) })
    }
}
