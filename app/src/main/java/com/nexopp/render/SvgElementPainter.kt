package com.nexopp.render

import com.nexopp.format.FontDescription
import com.nexopp.format.XoppColor.alpha
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Page
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.xml.XmlWriter
import com.nexopp.render.SvgFormat.num
import com.nexopp.render.SvgFormat.opacity
import com.nexopp.render.SvgFormat.rgb
import java.util.Base64

/**
 * The non-stroke half of the SVG export: text boxes, images and LaTeX images. The stroke and
 * background geometry lives in [SvgExporter] and [SvgBackgroundPainter]; this object keeps the
 * element markup out of the exporter so each file stays one job.
 *
 * Text mirrors [PdfVectorPainter]: no `Paint` is available on an export thread, so the baseline
 * geometry comes from the [TextBlock] ratios rather than real font metrics. Images are embedded as
 * `data:` URIs, so an exported SVG is self-contained and needs no sidecar files.
 */
internal object SvgElementPainter {

    /**
     * True when [page] will emit at least one `<image>` — an [ImageElement], or a
     * [TexImageElement] that carries a rendered PNG. The root `<svg>` only declares the `xlink`
     * namespace when this holds, so a page of pure ink stays free of an unused declaration.
     */
    fun hasImages(page: Page): Boolean = page.layers.any { layer ->
        layer.elements.any { it is ImageElement || (it is TexImageElement && it.data != null) }
    }

    /**
     * A `<text>` box: one `<tspan>` per wrapped line so the line breaks survive. SVG places text on
     * its baseline like the model does, so the first `dy` is the ascent below the box top and each
     * later line advances by one line height.
     */
    fun text(w: XmlWriter, t: TextElement) {
        val lines = TextBlock.lines(t.content)
        if (lines.all { it.isEmpty() }) return
        val fd = FontDescription.parse(t.font)
        w.start("text")
            .attr("x", num(t.x))
            .attr("y", num(t.y))
            .attr("font-family", svgFamily(fd.family))
            .attr("font-size", num(t.size))
        if (fd.bold) w.attr("font-weight", "bold")
        if (fd.italic) w.attr("font-style", "italic")
        w.attr("fill", rgb(t.color))
        if (t.color.alpha < 0xFF) w.attr("fill-opacity", opacity(t.color.alpha))
        for (i in lines.indices) {
            val dy = if (i == 0) t.size * TextBlock.ASCENT_RATIO else t.size * TextBlock.LINE_HEIGHT_RATIO
            w.start("tspan").attr("x", num(t.x)).attr("dy", num(dy)).text(lines[i]).end()
        }
        w.end().newline()
    }

    /** An `<image>` covering the pt box, with [data] embedded inline as a base64 `data:` URI. */
    fun image(w: XmlWriter, left: Double, top: Double, right: Double, bottom: Double, data: ByteArray) {
        if (data.isEmpty()) return
        w.start("image")
            .attr("x", num(left))
            .attr("y", num(top))
            .attr("width", num(right - left))
            .attr("height", num(bottom - top))
            .attr("xlink:href", "data:${mimeOf(data)};base64,${Base64.getEncoder().encodeToString(data)}")
            .end().newline()
    }

    /**
     * The desktop family names map onto the generic CSS families, exactly as [ElementRenderer] maps
     * them onto the Android ones; an unknown family is passed through for the viewer to resolve.
     */
    private fun svgFamily(family: String): String = when (family.lowercase()) {
        "sans", "sans-serif" -> "sans-serif"
        "serif", "times", "times new roman" -> "serif"
        "monospace", "mono", "courier", "courier new" -> "monospace"
        else -> family
    }

    /**
     * The `.xopp` model keeps embedded pictures as raw encoded bytes without recording their type,
     * so the `data:` URI's media type comes from the file signature. PNG is the desktop default and
     * the fallback for anything unrecognised.
     */
    private fun mimeOf(data: ByteArray): String = when {
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
        else -> "image/png"
    }
}
