package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Page

/**
 * Draws a fresh (non-PDF) page background — the sheet fill plus its ruling — onto a PDFBox content
 * stream as vector primitives, mirroring [BackgroundRenderer] at scale 1. Used only when a page has
 * no preserved PDF page behind it (a `solid` background, or a `pdf`/`pixmap` whose source isn't
 * available); PDF-backed pages keep their original vector content instead. Line spacings, colours
 * and the ruling geometry ([BackgroundGrid]) match the editor so the flatten looks the same.
 */
object PdfBackgroundPainter {

    private const val RULE_SPACING_PT = 24.0
    private const val GRID_SPACING_PT = 14.17
    private const val MARGIN_PT = 72.0
    private const val DOT_HALF_PT = 1.0f

    private const val LINE_RGB = 0xA9C7E8
    private const val MARGIN_RGB = 0xE79B9B
    private const val DOT_RGB = 0x8FB0D6

    fun draw(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        val solid = page.background as? Background.Solid
        fill(cs, page, t, solid?.color ?: WHITE)
        when (solid?.style) {
            "lined" -> horizontals(cs, page, t)
            "ruled" -> { horizontals(cs, page, t); marginLine(cs, page, t) }
            "graph" -> grid(cs, page, t)
            "dotted" -> dots(cs, page, t)
            else -> Unit // "plain", unknown, or non-solid: bare sheet
        }
    }

    private fun fill(cs: PDPageContentStream, page: Page, t: PdfPageTransform, color: Int) {
        cs.setNonStrokingColor((color ushr 16) and 0xFF, (color ushr 8) and 0xFF, color and 0xFF)
        cs.addRect(t.x(0.0), t.y(page.height), page.width.toFloat(), page.height.toFloat())
        cs.fill()
    }

    private fun horizontals(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        stroking(cs, LINE_RGB, 1f)
        for (y in BackgroundGrid.lines(page.height, RULE_SPACING_PT)) {
            cs.moveTo(t.x(0.0), t.y(y)); cs.lineTo(t.x(page.width), t.y(y)); cs.stroke()
        }
    }

    private fun marginLine(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        stroking(cs, MARGIN_RGB, 1.5f)
        cs.moveTo(t.x(MARGIN_PT), t.y(0.0)); cs.lineTo(t.x(MARGIN_PT), t.y(page.height)); cs.stroke()
    }

    private fun grid(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        stroking(cs, LINE_RGB, 1f)
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            cs.moveTo(t.x(0.0), t.y(y)); cs.lineTo(t.x(page.width), t.y(y)); cs.stroke()
        }
        for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
            cs.moveTo(t.x(x), t.y(0.0)); cs.lineTo(t.x(x), t.y(page.height)); cs.stroke()
        }
    }

    /** Dots have no PDF primitive; approximate each with a tiny filled square at the intersection. */
    private fun dots(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        cs.setNonStrokingColor((DOT_RGB ushr 16) and 0xFF, (DOT_RGB ushr 8) and 0xFF, DOT_RGB and 0xFF)
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
                cs.addRect(t.x(x) - DOT_HALF_PT, t.y(y) - DOT_HALF_PT, DOT_HALF_PT * 2, DOT_HALF_PT * 2)
            }
        }
        cs.fill()
    }

    private fun stroking(cs: PDPageContentStream, rgb: Int, width: Float) {
        cs.setStrokingColor((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF)
        cs.setLineWidth(width)
    }

    private const val WHITE = 0xFFFFFF
}
