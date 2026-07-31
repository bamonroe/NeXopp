package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Page
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Flattens a [Document] to a PDF using the framework [PdfDocument] (dependency-free — see the goal
 * in `docs/architecture.md`). Each `.xopp` page becomes one PDF page in points (the `.xopp` unit ==
 * the PDF unit, 1/72"): the background is drawn first — a rasterised `pdf` page from [pdfSource], or
 * a solid sheet with its ruling — then every layer's strokes and elements on top, merging the
 * annotations and the original PDF into a single sheet. Reuses [BackgroundRenderer]/[PageRenderer]
 * at scale 1 so the output matches the editor.
 */
class PdfExporter(private val pdfSource: PdfPageCache?) {

    private val strokes = StrokePainter()
    private val elements = ElementRenderer()

    fun export(doc: Document, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            doc.pages.forEachIndexed { i, page -> writePage(pdf, page, i) }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun writePage(pdf: PdfDocument, page: Page, index: Int) {
        val w = page.width.roundToInt().coerceAtLeast(1)
        val h = page.height.roundToInt().coerceAtLeast(1)
        val pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, index + 1).create())
        // A PageBox at scale 1, origin (0,0): view space == page point space for the export canvas.
        val box = PageBox(index, 0f, 0f, page.height.toFloat(), 1f, page)
        BackgroundRenderer.draw(pdfPage.canvas, box, 0f, 0f, pdfBitmapFor(page, w))
        PageRenderer.drawElements(pdfPage.canvas, page, 1f, 0f, 0f, strokes, elements)
        pdf.finishPage(pdfPage)
    }

    /** The `pdf` background rasterised at [EXPORT_SCALE]× the point width for a crisp flatten. */
    private fun pdfBitmapFor(page: Page, widthPt: Int): Bitmap? {
        val bg = page.background as? Background.Pdf ?: return null
        return pdfSource?.render(bg.pageNo, widthPt * EXPORT_SCALE)
    }

    private companion object {
        const val EXPORT_SCALE = 2
    }
}
