package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.RawElement
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.xml.XmlWriter
import java.util.Base64
import java.util.Locale

/** Serialises a [Document] back to `.xopp` XML, mirroring desktop Xournal++'s element order. */
class XoppWriter(out: Appendable) {

    private val w = XmlWriter(out)

    fun write(doc: Document) {
        w.prolog()
        w.start("xournal").attr("creator", doc.creator).attr("fileversion", doc.fileVersion)
        w.newline()
        w.start("title").text(doc.title ?: DEFAULT_TITLE).end().newline()
        doc.preview?.let { w.start("preview").rawText(it).end().newline() }
        for (page in doc.pages) writePage(page)
        w.end() // xournal
        w.newline()
    }

    private fun writePage(page: Page) {
        w.start("page")
        writeExtras(page.extraAttrs)
        w.attr("width", num(page.width)).attr("height", num(page.height)).newline()
        writeBackground(page.background)
        w.newline()
        for (layer in page.layers) writeLayer(layer)
        w.end() // page
        w.newline()
    }

    private fun writeBackground(bg: Background) {
        w.start("background")
        writeExtras(bg.extraAttrs)
        when (bg) {
            is Background.Solid ->
                w.attr("type", "solid").attr("color", XoppColor.format(bg.color))
                    .attr("style", bg.style)
            is Background.Pixmap ->
                w.attr("type", "pixmap").attr("domain", bg.domain).attr("filename", bg.filename)
            is Background.Pdf -> {
                w.attr("type", "pdf")
                bg.domain?.let { w.attr("domain", it) }
                bg.filename?.let { w.attr("filename", it) }
                // On-disk `pageno` is 1-based (desktop Xournal++ convention); we keep it 0-based
                // internally to index Android's PdfRenderer directly. See XoppReader for the inverse.
                w.attr("pageno", (bg.pageNo + 1).toString())
            }
        }
        w.end()
    }

    private fun writeLayer(layer: Layer) {
        w.start("layer")
        writeExtras(layer.extraAttrs)
        layer.name?.let { w.attr("name", it) }
        w.newline()
        for (el in layer.elements) when (el) {
            is Stroke -> writeStroke(el)
            is TextElement -> writeText(el)
            is ImageElement -> writeImage(el)
            is TexImageElement -> writeTexImage(el)
            is RawElement -> writeRaw(el)
        }
        w.end() // layer
        w.newline()
    }

    private fun writeStroke(s: Stroke) {
        w.start("stroke").attr("tool", s.tool.xml)
        for ((k, v) in s.extraAttrs) w.attr(k, v)
        w.attr("color", XoppColor.format(s.color))
        val width = if (s.uniformWidth || s.points.isEmpty()) {
            num(s.points.firstOrNull()?.width ?: 1.0)
        } else {
            s.points.joinToString(" ") { num(it.width) }
        }
        w.attr("width", width)
        s.fill?.let { w.attr("fill", it.toString()) }
        if (s.lineStyle != LineStyle.PLAIN) w.attr("style", s.lineStyle.xml)
        s.capStyle?.let { w.attr("capStyle", it) }
        val coords = buildString {
            s.points.forEachIndexed { i, p ->
                if (i > 0) append(' ')
                append(num(p.x)).append(' ').append(num(p.y))
            }
        }
        w.text(coords).end().newline()
    }

    private fun writeText(t: TextElement) {
        w.start("text")
        writeExtras(t.extraAttrs)
        w.attr("font", t.font).attr("size", num(t.size))
            .attr("x", num(t.x)).attr("y", num(t.y)).attr("color", XoppColor.format(t.color))
            .text(t.content).end().newline()
    }

    private fun writeImage(img: ImageElement) {
        w.start("image")
        writeExtras(img.extraAttrs)
        w.attr("left", num(img.left)).attr("top", num(img.top))
            .attr("right", num(img.right)).attr("bottom", num(img.bottom))
            .rawText(Base64.getEncoder().encodeToString(img.data)).end().newline()
    }

    private fun writeTexImage(t: TexImageElement) {
        w.start("teximage")
        writeExtras(t.extraAttrs)
        // Older files carry the LaTeX source in the body instead of a `text` attribute; re-emit
        // whichever shape the file was authored in rather than converting it (see TexImageElement).
        if (t.latexInAttribute) w.attr("text", t.latex)
        w.attr("color", XoppColor.format(t.color))
            .attr("left", num(t.left)).attr("top", num(t.top))
            .attr("right", num(t.right)).attr("bottom", num(t.bottom))
        // The body carries the rendered PNG when we have one. Otherwise it carries the LaTeX source
        // only in the old body-form: writing the source into the body of an attribute-form element
        // would make the next read decode it as base64 and hand back garbage.
        val png = t.data
        when {
            png != null -> w.rawText(Base64.getEncoder().encodeToString(png))
            !t.latexInAttribute -> w.text(t.latex)
        }
        w.end().newline()
    }

    /** Re-emit an element we don't model exactly as it was read (see [RawElement]). */
    private fun writeRaw(el: RawElement) {
        w.start(el.name)
        writeExtras(el.attrs)
        if (el.body.isNotEmpty()) w.rawText(el.body)
        w.end().newline()
    }

    /** Attributes we don't interpret, written first so they keep their place in the element. */
    private fun writeExtras(extras: Map<String, String>) {
        for ((k, v) in extras) w.attr(k, v)
    }

    companion object {
        /** The banner desktop Xournal++ writes; used when the document carries no title of its own. */
        const val DEFAULT_TITLE = "Xournal++ document - see https://xournalpp.github.io/"

        /** Fixed 8-decimal form, matching desktop Xournal++'s writer; locale-independent. */
        private fun num(v: Double): String = String.format(Locale.US, "%.8f", v)
    }
}
