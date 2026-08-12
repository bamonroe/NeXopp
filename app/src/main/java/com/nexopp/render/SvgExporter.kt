package com.nexopp.render

import com.nexopp.format.XoppColor.alpha
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.xml.XmlWriter
import com.nexopp.render.SvgFormat.num
import com.nexopp.render.SvgFormat.opacity
import com.nexopp.render.SvgFormat.rgb
import java.io.Writer

/**
 * Flattens **one** [Page] to a standalone SVG 1.1 document. The vector twin of [PageRasterizer],
 * and the same job [PdfVectorPainter] does against PDF operators — the geometry decisions live in
 * [StrokePainter] and [BackgroundGrid], shared by all three, so a page looks the same on screen,
 * in a PDF and in an SVG.
 *
 * SVG's y axis grows **down**, exactly like the `.xopp` model, so there is no flip here and
 * [PdfPageTransform] is deliberately not used: page coordinates are written verbatim in pt.
 *
 * Markup goes through [XmlWriter] so every value is escaped, and every number through [SvgFormat]
 * so a comma-decimal locale can't corrupt the output.
 *
 * Scope: background and strokes only. Text, images and LaTeX elements are skipped silently — they
 * are the follow-up task "Extend the SVG exporter to text, images and LaTeX elements".
 */
class SvgExporter {

    /** Write [page] to [out] as a complete SVG document. The caller owns closing [out]. */
    fun export(page: Page, out: Writer) {
        val w = XmlWriter(out)
        w.prolog()
        w.start("svg")
            .attr("xmlns", SVG_NS)
            .attr("version", "1.1")
            .attr("width", num(page.width) + "pt")
            .attr("height", num(page.height) + "pt")
            .attr("viewBox", "0 0 ${num(page.width)} ${num(page.height)}")
            .newline()
        SvgBackgroundPainter.draw(w, page)
        for (layer in page.layers) {
            for (element in layer.elements) {
                // TODO: text/image/teximage — "Extend the SVG exporter to text, images and LaTeX".
                if (element is Stroke) stroke(w, element)
            }
        }
        w.end().newline()
        out.flush()
    }

    /**
     * One stroke. A uniform-width stroke (highlighter band or dashed outline) is a single `<path>`;
     * a pressure stroke becomes one `<path>` per point pair so the width can taper between
     * vertices, mirroring how [StrokePainter] draws it on screen.
     */
    private fun stroke(w: XmlWriter, s: Stroke) {
        val pts = s.points
        if (pts.size < 2) return
        s.fill?.let { fillPath(w, s.color, it, pts) }
        val argb = StrokePainter.renderColor(s.tool, s.color)
        if (StrokePainter.RenderMode.of(s) == StrokePainter.RenderMode.Pressure) {
            pressurePaths(w, pts, argb)
        } else {
            val width = StrokePainter.bandWidth(pts)
            linePath(w, polyline(pts, close = false), argb, width, StrokePainter.dashIntervalsPt(s.lineStyle, width))
        }
    }

    /** Flood the closed outline with the stroke colour at the fill alpha, under the outline itself. */
    private fun fillPath(w: XmlWriter, color: Int, fill: Int, pts: List<StrokePoint>) {
        w.start("path")
            .attr("d", polyline(pts, close = true))
            .attr("fill", rgb(color))
            .attr("fill-opacity", opacity(fill and 0xFF))
            .attr("stroke", "none")
            .end().newline()
    }

    /** The pen: a per-segment path, each carrying the mean width of the pair it spans. */
    private fun pressurePaths(w: XmlWriter, pts: List<StrokePoint>, argb: Int) {
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = "M ${num(a.x)} ${num(a.y)} L ${num(b.x)} ${num(b.y)}"
            linePath(w, d, argb, (a.width + b.width) / 2.0, dash = null)
        }
    }

    private fun linePath(w: XmlWriter, d: String, argb: Int, width: Double, dash: FloatArray?) {
        w.start("path")
            .attr("d", d)
            .attr("fill", "none")
            .attr("stroke", rgb(argb))
            .attr("stroke-width", num(width))
        if (argb.alpha < 0xFF) w.attr("stroke-opacity", opacity(argb.alpha))
        w.attr("stroke-linecap", "round").attr("stroke-linejoin", "round")
        dash?.let { w.attr("stroke-dasharray", it.joinToString(",") { v -> num(v.toDouble()) }) }
        w.end().newline()
    }

    /** The `d` of a polyline through [pts], optionally closed with `Z`. */
    private fun polyline(pts: List<StrokePoint>, close: Boolean): String = buildString {
        append("M ").append(num(pts[0].x)).append(' ').append(num(pts[0].y))
        for (i in 1 until pts.size) {
            append(" L ").append(num(pts[i].x)).append(' ').append(num(pts[i].y))
        }
        if (close) append(" Z")
    }

    private companion object {
        const val SVG_NS = "http://www.w3.org/2000/svg"
    }
}
