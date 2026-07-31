package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Page
import java.io.OutputStream

/**
 * Flattens a [Document] to a PDF with **PDFBox** ([com.tom_roush]) so original PDF pages keep their
 * **vector** content instead of being rasterised. For a `pdf`-backed page whose source is available
 * ([pdfSource].source), the untouched source page is imported verbatim and the annotations are
 * appended as a vector overlay; every other page becomes a fresh sheet with a vector background plus
 * the same overlay. Nothing is rasterised except user-placed bitmap images, which are already
 * raster. A no-op import→export therefore round-trips the PDF at ~its original size and fidelity.
 *
 * Rotated source pages (`/Rotate` 90/180/270) are handled: the overlay is authored in the page's
 * visual space, so a [PdfOverlayMatrix] pre-transforms the content stream into the page's unrotated
 * space and the viewer's `/Rotate` cancels back to the drawn position (see [preservedPage]).
 */
class PdfExporter(private val pdfSource: PdfPageCache?) {

    private val painter = PdfVectorPainter()

    fun export(doc: Document, out: OutputStream) {
        val source = pdfSource?.source?.let { runCatching { PDDocument.load(it) }.getOrNull() }
        val outDoc = PDDocument()
        try {
            doc.pages.forEach { page -> writePage(outDoc, source, page) }
            outDoc.save(out)
        } finally {
            outDoc.close()
            source?.close()
        }
    }

    private fun writePage(outDoc: PDDocument, source: PDDocument?, page: Page) {
        val bg = page.background
        if (bg is Background.Pdf && source != null && bg.pageNo in 0 until source.numberOfPages) {
            preservedPage(outDoc, source, bg.pageNo, page)
        } else {
            freshPage(outDoc, page)
        }
    }

    /** Import the original page's vector content unchanged, then append the annotation overlay. */
    private fun preservedPage(outDoc: PDDocument, source: PDDocument, pageNo: Int, page: Page) {
        val pdPage = outDoc.importPage(source.getPage(pageNo))
        val crop = pdPage.cropBox
        // importPage copies the source /Rotate; map the visual-space overlay into unrotated content
        // space so the viewer's rotation lands it where it was drawn. For /Rotate 0 this is just the
        // crop-origin shift, so the per-point transform runs at the origin.
        val m = PdfOverlayMatrix.forPage(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, pdPage.rotation)
        val transform = PdfPageTransform(0f, 0f, m.visualHeight)
        PDPageContentStream(outDoc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
            painter.paint(outDoc, cs, page, transform)
        }
    }

    /** No source PDF page: a fresh sheet with a vector background plus the annotation overlay. */
    private fun freshPage(outDoc: PDDocument, page: Page) {
        val pdPage = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
        outDoc.addPage(pdPage)
        val transform = PdfPageTransform(0f, 0f, page.height.toFloat())
        PDPageContentStream(outDoc, pdPage).use { cs ->
            PdfBackgroundPainter.draw(cs, page, transform)
            painter.paint(outDoc, cs, page, transform)
        }
    }
}
