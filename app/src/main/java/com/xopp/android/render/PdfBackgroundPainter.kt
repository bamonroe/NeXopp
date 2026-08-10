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

    // Shared with the editor via [BackgroundGrid] so the flatten can't drift from what's on screen.
    private const val RULE_SPACING_PT = BackgroundGrid.RULE_SPACING_PT
    private const val GRID_SPACING_PT = BackgroundGrid.GRID_SPACING_PT
    private const val MARGIN_PT = BackgroundGrid.MARGIN_PT
    private const val DOT_HALF_PT = 1.0f

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
        cs.setNonStrokingArgb(color)
        cs.addRect(t.x(0.0), t.y(page.height), page.width.toFloat(), page.height.toFloat())
        cs.fill()
    }

    private fun horizontals(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        cs.setStrokingArgb(BackgroundGrid.LINE_RGB)
        cs.setLineWidth(1f)
        for (y in BackgroundGrid.lines(page.height, RULE_SPACING_PT)) {
            cs.moveTo(t.x(0.0), t.y(y)); cs.lineTo(t.x(page.width), t.y(y)); cs.stroke()
        }
    }

    private fun marginLine(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        cs.setStrokingArgb(BackgroundGrid.MARGIN_RGB)
        cs.setLineWidth(1.5f)
        cs.moveTo(t.x(MARGIN_PT), t.y(0.0)); cs.lineTo(t.x(MARGIN_PT), t.y(page.height)); cs.stroke()
    }

    private fun grid(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        cs.setStrokingArgb(BackgroundGrid.LINE_RGB)
        cs.setLineWidth(1f)
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            cs.moveTo(t.x(0.0), t.y(y)); cs.lineTo(t.x(page.width), t.y(y)); cs.stroke()
        }
        for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
            cs.moveTo(t.x(x), t.y(0.0)); cs.lineTo(t.x(x), t.y(page.height)); cs.stroke()
        }
    }

    /** Dots have no PDF primitive; approximate each with a tiny filled square at the intersection. */
    private fun dots(cs: PDPageContentStream, page: Page, t: PdfPageTransform) {
        cs.setNonStrokingArgb(BackgroundGrid.DOT_RGB)
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
                cs.addRect(t.x(x) - DOT_HALF_PT, t.y(y) - DOT_HALF_PT, DOT_HALF_PT * 2, DOT_HALF_PT * 2)
            }
        }
        cs.fill()
    }

    private const val WHITE = 0xFFFFFF
}
