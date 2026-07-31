package com.xopp.android.render

import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.xopp.android.format.FontDescription
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool

/**
 * Draws a [Page]'s annotation layers onto a PDFBox [PDPageContentStream] as **vector** content, so
 * they overlay the preserved PDF page (or a fresh sheet) without rasterising. Mirrors the on-screen
 * [StrokePainter]/[ElementRenderer] geometry at scale 1, flipping y via [PdfPageTransform] into PDF
 * user space. Pen strokes taper per segment; the highlighter is one constant-width translucent path
 * (a single stroke op, so its alpha doesn't bead at self-overlaps — see [StrokePainter]). Text is
 * drawn with the base-14 fonts; images embed losslessly. Any element that can't be encoded is
 * skipped rather than aborting the export.
 */
class PdfVectorPainter {

    fun paint(doc: PDDocument, cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        for (layer in page.layers) {
            for (element in layer.elements) {
                runCatching {
                    when (element) {
                        is Stroke -> stroke(cs, element, t)
                        is TextElement -> text(cs, element, t)
                        is ImageElement -> image(doc, cs, element, t)
                        is TexImageElement -> tex(cs, element, t)
                    }
                }
            }
        }
    }

    private fun stroke(cs: PDPageContentStream, s: Stroke, t: PdfPageTransform) {
        val pts = s.points
        if (pts.size < 2) return
        val argb = StrokePainter.renderColor(s.tool, s.color)
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(alphaState(alpha(argb)))
        cs.setStrokingColor(red(argb), green(argb), blue(argb))
        cs.setLineCapStyle(1) // round
        cs.setLineJoinStyle(1) // round
        if (s.tool == Tool.HIGHLIGHTER) band(cs, s, t) else pressureLine(cs, s, t)
        cs.restoreGraphicsState()
    }

    /** The pen: a per-segment stroke so the line width can track pressure between vertices. */
    private fun pressureLine(cs: PDPageContentStream, s: Stroke, t: PdfPageTransform) {
        val pts = s.points
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            cs.setLineWidth(((a.width + b.width) / 2.0).toFloat())
            cs.moveTo(t.x(a.x), t.y(a.y))
            cs.lineTo(t.x(b.x), t.y(b.y))
            cs.stroke()
        }
    }

    /** The highlighter: one constant-width polyline stroked once so its translucency stays flat. */
    private fun band(cs: PDPageContentStream, s: Stroke, t: PdfPageTransform) {
        val pts = s.points
        cs.setLineWidth(StrokePainter.bandWidth(pts).toFloat())
        cs.moveTo(t.x(pts[0].x), t.y(pts[0].y))
        for (i in 1 until pts.size) cs.lineTo(t.x(pts[i].x), t.y(pts[i].y))
        cs.stroke()
    }

    private fun text(cs: PDPageContentStream, tx: TextElement, t: PdfPageTransform) {
        val font = fontFor(tx.font)
        val size = tx.size.toFloat()
        val ascent = tx.size * ASCENT_RATIO
        val lineHeight = tx.size * LINE_HEIGHT_RATIO
        cs.beginText()
        cs.setNonStrokingColor(red(tx.color), green(tx.color), blue(tx.color))
        cs.setFont(font, size)
        cs.newLineAtOffset(t.x(tx.x), t.y(tx.y + ascent))
        val lines = TextBlock.lines(tx.content)
        for (i in lines.indices) {
            cs.showText(sanitize(lines[i]))
            if (i < lines.lastIndex) cs.newLineAtOffset(0f, -lineHeight.toFloat())
        }
        cs.endText()
    }

    private fun image(doc: PDDocument, cs: PDPageContentStream, img: ImageElement, t: PdfPageTransform) {
        val bmp = BitmapFactory.decodeByteArray(img.data, 0, img.data.size) ?: return
        val xobj = LosslessFactory.createFromImage(doc, bmp)
        cs.drawImage(
            xobj,
            t.x(img.left), t.y(img.bottom),
            (img.right - img.left).toFloat(), (img.bottom - img.top).toFloat(),
        )
    }

    /** A `<teximage>` has no vector glyphs in our model; fall back to its raw source in monospace. */
    private fun tex(cs: PDPageContentStream, tex: TexImageElement, t: PdfPageTransform) {
        if (tex.latex.isBlank()) return
        val size = ((tex.bottom - tex.top) * 0.5).coerceIn(6.0, 14.0)
        cs.beginText()
        cs.setNonStrokingColor(red(tex.color), green(tex.color), blue(tex.color))
        cs.setFont(PDType1Font.COURIER, size.toFloat())
        cs.newLineAtOffset(t.x(tex.left), t.y(tex.top + size))
        cs.showText(sanitize(tex.latex))
        cs.endText()
    }

    private fun fontFor(font: String): PDFont {
        val fd = FontDescription.parse(font)
        val bold = fd.bold
        val italic = fd.italic
        return when (fd.family.lowercase()) {
            "serif", "times", "times new roman" -> serif(bold, italic)
            "monospace", "mono", "courier", "courier new" -> mono(bold, italic)
            else -> sans(bold, italic)
        }
    }

    private fun sans(b: Boolean, i: Boolean) = when {
        b && i -> PDType1Font.HELVETICA_BOLD_OBLIQUE
        b -> PDType1Font.HELVETICA_BOLD
        i -> PDType1Font.HELVETICA_OBLIQUE
        else -> PDType1Font.HELVETICA
    }

    private fun serif(b: Boolean, i: Boolean) = when {
        b && i -> PDType1Font.TIMES_BOLD_ITALIC
        b -> PDType1Font.TIMES_BOLD
        i -> PDType1Font.TIMES_ITALIC
        else -> PDType1Font.TIMES_ROMAN
    }

    private fun mono(b: Boolean, i: Boolean) = when {
        b && i -> PDType1Font.COURIER_BOLD_OBLIQUE
        b -> PDType1Font.COURIER_BOLD
        i -> PDType1Font.COURIER_OBLIQUE
        else -> PDType1Font.COURIER
    }

    private companion object {
        const val ASCENT_RATIO = 0.75
        const val LINE_HEIGHT_RATIO = 1.2

        fun alphaState(a: Float) = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = a
            nonStrokingAlphaConstant = a
        }

        fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
        fun red(argb: Int): Int = (argb ushr 16) and 0xFF
        fun green(argb: Int): Int = (argb ushr 8) and 0xFF
        fun blue(argb: Int): Int = argb and 0xFF

        /** The base-14 fonts only encode WinAnsi; drop anything outside it so `showText` can't throw. */
        fun sanitize(s: String): String =
            buildString(s.length) { for (c in s) append(if (c.code in 0x20..0xFF) c else '?') }
    }
}
