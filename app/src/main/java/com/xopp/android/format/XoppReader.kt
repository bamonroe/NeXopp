package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import com.xopp.android.format.xml.XmlPullReader
import com.xopp.android.format.xml.XmlPullReader.Event
import java.util.Base64

/** Builds a [Document] from decompressed `.xopp` XML. Attributes we don't model are preserved. */
class XoppReader(xml: String) {

    private val r = XmlPullReader(xml)

    /** Attributes handled explicitly on `<stroke>`; everything else is preserved verbatim. */
    private val strokeKnownAttrs = setOf("tool", "color", "width", "capStyle", "style", "fill")

    fun read(): Document {
        val pages = mutableListOf<Page>()
        var creator = "xopp_android"
        var fileVersion = "4"
        var preview: String? = null
        while (r.next() != Event.EOF) {
            if (r.event == Event.START) when (r.name) {
                "xournal" -> {
                    creator = r.attr("creator") ?: creator
                    fileVersion = r.attr("fileversion") ?: fileVersion
                }
                "preview" -> preview = readTextContent().trim()
                "page" -> pages += readPage()
            }
        }
        return Document(creator, fileVersion, pages, preview)
    }

    private fun readPage(): Page {
        val width = r.attr("width")?.toDoubleOrNull() ?: 0.0
        val height = r.attr("height")?.toDoubleOrNull() ?: 0.0
        var background: Background = Background.Solid(0xFFFFFFFF.toInt(), "plain")
        val layers = mutableListOf<Layer>()
        while (r.next() != Event.EOF) {
            when (r.event) {
                Event.START -> when (r.name) {
                    "background" -> background = readBackground()
                    "layer" -> layers += readLayer()
                }
                Event.END -> if (r.name == "page") break
                else -> {}
            }
        }
        return Page(width, height, background, layers)
    }

    private fun readBackground(): Background = when (r.attr("type")) {
        "pixmap" -> Background.Pixmap(
            domain = r.attr("domain") ?: "absolute",
            filename = r.attr("filename") ?: "",
        )
        "pdf" -> Background.Pdf(
            filename = r.attr("filename"),
            // `pageno` is 1-based on disk (desktop Xournal++); store it 0-based to index
            // Android's PdfRenderer directly. Missing pageno defaults to the first page.
            pageNo = (r.attr("pageno")?.toIntOrNull() ?: 1) - 1,
            domain = r.attr("domain"),
        )
        else -> Background.Solid(
            color = XoppColor.parse(r.attr("color")),
            style = r.attr("style") ?: "plain",
        )
    }

    private fun readLayer(): Layer {
        val name = r.attr("name")
        val elements = mutableListOf<Element>()
        while (r.next() != Event.EOF) {
            when (r.event) {
                Event.START -> when (r.name) {
                    "stroke" -> elements += readStroke()
                    "text" -> elements += readText()
                    "image" -> elements += readImage()
                    "teximage" -> elements += readTexImage()
                }
                Event.END -> if (r.name == "layer") break
                else -> {}
            }
        }
        return Layer(elements, name)
    }

    private fun readStroke(): Stroke {
        val tool = Tool.fromXml(r.attr("tool"))
        val color = XoppColor.parse(r.attr("color"))
        val capStyle = r.attr("capStyle")
        val lineStyle = LineStyle.fromXml(r.attr("style"))
        val fill = r.attr("fill")?.toIntOrNull()?.coerceIn(0, 255)
        val widths = (r.attr("width") ?: "").trim().split(WS).filter { it.isNotEmpty() }
            .map { it.toDoubleOrNull() ?: 1.0 }
        val extra = r.attributes()
            .filterKeys { it !in strokeKnownAttrs }
            .toMap(LinkedHashMap<String, String>())
        val coords = readTextContent().trim().split(WS).filter { it.isNotEmpty() }
            .map { it.toDoubleOrNull() ?: 0.0 }
        val uniform = widths.size <= 1
        val fallback = widths.firstOrNull() ?: 1.0
        val points = ArrayList<StrokePoint>(coords.size / 2)
        var i = 0
        var p = 0
        while (i + 1 < coords.size) {
            val w = if (uniform) fallback else widths.getOrElse(p) { fallback }
            points += StrokePoint(coords[i], coords[i + 1], w)
            i += 2
            p++
        }
        return Stroke(tool, color, capStyle, points, uniform, lineStyle, fill, extra)
    }

    private fun readText(): TextElement {
        val font = r.attr("font") ?: "Sans"
        val size = r.attr("size")?.toDoubleOrNull() ?: 12.0
        val x = r.attr("x")?.toDoubleOrNull() ?: 0.0
        val y = r.attr("y")?.toDoubleOrNull() ?: 0.0
        val color = XoppColor.parse(r.attr("color"))
        val content = readTextContent()
        return TextElement(font, size, x, y, color, content)
    }

    private fun readImage(): ImageElement {
        val left = r.attr("left")?.toDoubleOrNull() ?: 0.0
        val top = r.attr("top")?.toDoubleOrNull() ?: 0.0
        val right = r.attr("right")?.toDoubleOrNull() ?: 0.0
        val bottom = r.attr("bottom")?.toDoubleOrNull() ?: 0.0
        val b64 = readTextContent().trim()
        val bytes = if (b64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(b64)
        return ImageElement(left, top, right, bottom, bytes)
    }

    private fun readTexImage(): TexImageElement {
        val left = r.attr("left")?.toDoubleOrNull() ?: 0.0
        val top = r.attr("top")?.toDoubleOrNull() ?: 0.0
        val right = r.attr("right")?.toDoubleOrNull() ?: 0.0
        val bottom = r.attr("bottom")?.toDoubleOrNull() ?: 0.0
        val color = XoppColor.parse(r.attr("color"))
        val attrLatex = r.attr("text")
        // With a `text` attribute the body is the desktop-rendered PNG (base64); without one,
        // older files put the LaTeX source itself in the body.
        val body = readTextContent().trim()
        val latex = attrLatex ?: body
        val data = if (attrLatex != null && body.isNotEmpty()) {
            runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull()
        } else {
            null
        }
        return TexImageElement(left, top, right, bottom, latex, color, data)
    }

    /** Collect text between the current START and its matching END. */
    private fun readTextContent(): String {
        val open = r.name
        val sb = StringBuilder()
        while (r.next() != Event.EOF) {
            when (r.event) {
                Event.TEXT -> sb.append(r.text)
                Event.END -> if (r.name == open) break
                else -> {}
            }
        }
        return sb.toString()
    }

    private companion object {
        val WS = Regex("\\s+")
    }
}
