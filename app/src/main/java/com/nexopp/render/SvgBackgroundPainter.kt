package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Page
import com.nexopp.format.xml.XmlWriter
import com.nexopp.render.SvgFormat.num
import com.nexopp.render.SvgFormat.rgb

/**
 * Writes a page's sheet fill and ruling as SVG primitives — the SVG twin of [PdfBackgroundPainter],
 * with the same spacings and colours (both take them from [BackgroundGrid]) so the three renderings
 * of a page — screen, PDF, SVG — can't drift apart.
 *
 * SVG shares the `.xopp` model's y-down axis, so unlike the PDF painter there is no flip: page
 * coordinates go out verbatim. A `pdf` or `pixmap` background gets only the plain sheet here; the
 * source image isn't embedded (see [SvgExporter]).
 */
internal object SvgBackgroundPainter {

    private const val RULE_SPACING_PT = BackgroundGrid.RULE_SPACING_PT
    private const val GRID_SPACING_PT = BackgroundGrid.GRID_SPACING_PT
    private const val MARGIN_PT = BackgroundGrid.MARGIN_PT
    private const val DOT_RADIUS_PT = 1.0

    private const val RULE_WIDTH_PT = 1.0
    private const val MARGIN_WIDTH_PT = 1.5

    private const val WHITE = 0xFFFFFF

    fun draw(w: XmlWriter, page: Page) {
        val solid = page.background as? Background.Solid
        sheet(w, page, solid?.color ?: WHITE)
        when (solid?.style) {
            "lined" -> horizontals(w, page)
            "ruled" -> { horizontals(w, page); marginLine(w, page) }
            "graph" -> grid(w, page)
            "dotted" -> dots(w, page)
            else -> Unit // "plain", unknown, or non-solid: bare sheet
        }
    }

    private fun sheet(w: XmlWriter, page: Page, color: Int) {
        w.start("rect")
            .attr("x", "0").attr("y", "0")
            .attr("width", num(page.width)).attr("height", num(page.height))
            .attr("fill", rgb(color))
            .end().newline()
    }

    private fun horizontals(w: XmlWriter, page: Page) {
        for (y in BackgroundGrid.lines(page.height, RULE_SPACING_PT)) {
            line(w, 0.0, y, page.width, y, BackgroundGrid.LINE_RGB, RULE_WIDTH_PT)
        }
    }

    private fun marginLine(w: XmlWriter, page: Page) {
        line(w, MARGIN_PT, 0.0, MARGIN_PT, page.height, BackgroundGrid.MARGIN_RGB, MARGIN_WIDTH_PT)
    }

    private fun grid(w: XmlWriter, page: Page) {
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            line(w, 0.0, y, page.width, y, BackgroundGrid.LINE_RGB, RULE_WIDTH_PT)
        }
        for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
            line(w, x, 0.0, x, page.height, BackgroundGrid.LINE_RGB, RULE_WIDTH_PT)
        }
    }

    /** SVG has a real circle primitive, so a dot is a dot here rather than the PDF path's tiny square. */
    private fun dots(w: XmlWriter, page: Page) {
        for (y in BackgroundGrid.lines(page.height, GRID_SPACING_PT)) {
            for (x in BackgroundGrid.lines(page.width, GRID_SPACING_PT)) {
                w.start("circle")
                    .attr("cx", num(x)).attr("cy", num(y)).attr("r", num(DOT_RADIUS_PT))
                    .attr("fill", rgb(BackgroundGrid.DOT_RGB))
                    .end().newline()
            }
        }
    }

    private fun line(w: XmlWriter, x1: Double, y1: Double, x2: Double, y2: Double, color: Int, width: Double) {
        w.start("line")
            .attr("x1", num(x1)).attr("y1", num(y1))
            .attr("x2", num(x2)).attr("y2", num(y2))
            .attr("stroke", rgb(color)).attr("stroke-width", num(width))
            .end().newline()
    }
}
