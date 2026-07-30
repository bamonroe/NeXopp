package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
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
        w.start("title").text(TITLE).end().newline()
        doc.preview?.let { w.start("preview").rawText(it).end().newline() }
        for (page in doc.pages) writePage(page)
        w.end() // xournal
        w.newline()
    }

    private fun writePage(page: Page) {
        w.start("page").attr("width", num(page.width)).attr("height", num(page.height)).newline()
        writeBackground(page.background)
        w.newline()
        for (layer in page.layers) writeLayer(layer)
        w.end() // page
        w.newline()
    }

    private fun writeBackground(bg: Background) {
        w.start("background")
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
                w.attr("pageno", bg.pageNo.toString())
            }
        }
        w.end()
    }

    private fun writeLayer(layer: Layer) {
        w.start("layer").newline()
        for (el in layer.elements) when (el) {
            is Stroke -> writeStroke(el)
            is TextElement -> writeText(el)
            is ImageElement -> writeImage(el)
            is TexImageElement -> writeTexImage(el)
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
        w.start("text").attr("font", t.font).attr("size", num(t.size))
            .attr("x", num(t.x)).attr("y", num(t.y)).attr("color", XoppColor.format(t.color))
            .text(t.content).end().newline()
    }

    private fun writeImage(img: ImageElement) {
        w.start("image").attr("left", num(img.left)).attr("top", num(img.top))
            .attr("right", num(img.right)).attr("bottom", num(img.bottom))
            .rawText(Base64.getEncoder().encodeToString(img.data)).end().newline()
    }

    private fun writeTexImage(t: TexImageElement) {
        w.start("teximage").attr("text", t.latex).attr("color", XoppColor.format(t.color))
            .attr("left", num(t.left)).attr("top", num(t.top))
            .attr("right", num(t.right)).attr("bottom", num(t.bottom))
            .text(t.latex).end().newline()
    }

    private companion object {
        const val TITLE = "Xournal++ document - see https://xournalpp.github.io/"

        /** Fixed 8-decimal form, matching desktop Xournal++'s writer; locale-independent. */
        fun num(v: Double): String = String.format(Locale.US, "%.8f", v)
    }
}
