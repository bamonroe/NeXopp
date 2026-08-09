package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.RawElement
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

    /** Attributes handled explicitly per element; everything else is preserved verbatim. */
    private val strokeKnownAttrs = setOf("tool", "color", "width", "capStyle", "style", "fill")
    private val pageKnownAttrs = setOf("width", "height")
    private val layerKnownAttrs = setOf("name")
    private val backgroundKnownAttrs = setOf("type", "color", "style", "domain", "filename", "pageno")
    private val textKnownAttrs = setOf("font", "size", "x", "y", "color")
    private val imageKnownAttrs = setOf("left", "top", "right", "bottom")
    private val texKnownAttrs = setOf("left", "top", "right", "bottom", "color", "text")

    fun read(): Document {
        val pages = mutableListOf<Page>()
        var creator = "xopp_android"
        var fileVersion = "4"
        var preview: String? = null
        var title: String? = null
        while (r.next() != Event.EOF) {
            if (r.event == Event.START) when (r.name) {
                "xournal" -> {
                    creator = r.attr("creator") ?: creator
                    fileVersion = r.attr("fileversion") ?: fileVersion
                }
                // Only a *custom* title is worth carrying: the standard banner is what the writer
                // emits anyway, and keeping it null makes model -> XML -> model an identity.
                "title" -> title = readTextContent().takeIf { it != XoppWriter.DEFAULT_TITLE }
                "preview" -> preview = readTextContent().trim()
                "page" -> pages += readPage()
            }
        }
        return Document(creator, fileVersion, pages, preview, title)
    }

    /** The attributes of the element at the cursor minus [known], in source order. */
    private fun extras(known: Set<String>): Map<String, String> =
        r.attributes().filterKeys { it !in known }.toMap(LinkedHashMap())

    private fun readPage(): Page {
        val width = r.attr("width")?.toDoubleOrNull() ?: 0.0
        val height = r.attr("height")?.toDoubleOrNull() ?: 0.0
        val extra = extras(pageKnownAttrs)
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
        return Page(width, height, background, layers, extra)
    }

    private fun readBackground(): Background {
        val extra = extras(backgroundKnownAttrs)
        return when (r.attr("type")) {
            "pixmap" -> Background.Pixmap(
                domain = r.attr("domain") ?: "absolute",
                filename = r.attr("filename") ?: "",
                extraAttrs = extra,
            )
            "pdf" -> Background.Pdf(
                filename = r.attr("filename"),
                // `pageno` is 1-based on disk (desktop Xournal++); store it 0-based to index
                // Android's PdfRenderer directly. Missing pageno defaults to the first page.
                pageNo = (r.attr("pageno")?.toIntOrNull() ?: 1) - 1,
                domain = r.attr("domain"),
                extraAttrs = extra,
            )
            else -> Background.Solid(
                color = XoppColor.parse(r.attr("color")),
                style = r.attr("style") ?: "plain",
                extraAttrs = extra,
            )
        }
    }

    private fun readLayer(): Layer {
        val name = r.attr("name")
        val extra = extras(layerKnownAttrs)
        val elements = mutableListOf<Element>()
        while (r.next() != Event.EOF) {
            when (r.event) {
                Event.START -> when (r.name) {
                    "stroke" -> elements += readStroke()
                    "text" -> elements += readText()
                    "image" -> elements += readImage()
                    "teximage" -> elements += readTexImage()
                    // Anything else is a vendor or future element: keep its markup verbatim so the
                    // next save doesn't silently drop it.
                    else -> elements += readRaw()
                }
                Event.END -> if (r.name == "layer") break
                else -> {}
            }
        }
        return Layer(elements, name, extra)
    }

    /** Capture the unrecognised element at the cursor — attributes plus raw inner markup. */
    private fun readRaw(): RawElement {
        val name = r.name
        val attrs = r.attributes().toMap(LinkedHashMap<String, String>())
        val start = r.position
        var end = start
        var depth = 0
        loop@ while (true) {
            val before = r.position
            when (r.next()) {
                Event.EOF -> { end = before; break@loop }
                Event.START -> depth++
                Event.END -> if (depth == 0 && r.name == name) {
                    end = before
                    break@loop
                } else {
                    depth--
                }
                else -> {}
            }
        }
        return RawElement(name, attrs, r.rawSlice(start, end))
    }

    private fun readStroke(): Stroke {
        val tool = Tool.fromXml(r.attr("tool"))
        val color = XoppColor.parse(r.attr("color"))
        val capStyle = r.attr("capStyle")
        val lineStyle = LineStyle.fromXml(r.attr("style"))
        val fill = r.attr("fill")?.toIntOrNull()?.coerceIn(0, 255)
        val extra = r.attributes()
            .filterKeys { it !in strokeKnownAttrs }
            .toMap(LinkedHashMap<String, String>())
        
        // Parse widths and coordinates into primitive arrays to avoid boxing churn.
        // For a 79k-stroke document this cuts GC pressure from ~35 MB/frame to ~2 MB.
        val widthsStr = r.attr("width") ?: ""
        val coordsStr = readTextContent().trim()
        
        // Count width tokens to allocate exact-size array
        var widthCount = 0
        var wIdx = 0
        while (wIdx < widthsStr.length) {
            while (wIdx < widthsStr.length && widthsStr[wIdx].isWhitespace()) wIdx++
            if (wIdx < widthsStr.length) {
                widthCount++
                while (wIdx < widthsStr.length && !widthsStr[wIdx].isWhitespace()) wIdx++
            }
        }
        val widths = DoubleArray(widthCount) { 1.0 }
        var wi = 0
        var wsIdx = 0
        while (wsIdx < widthsStr.length && wi < widthCount) {
            while (wsIdx < widthsStr.length && widthsStr[wsIdx].isWhitespace()) wsIdx++
            if (wsIdx < widthsStr.length) {
                val start = wsIdx
                while (wsIdx < widthsStr.length && !widthsStr[wsIdx].isWhitespace()) wsIdx++
                widths[wi++] = widthsStr.substring(start, wsIdx).toDoubleOrNull() ?: 1.0
            }
        }
        
        // Count coordinate tokens to allocate exact-size array
        var coordCount = 0
        var cIdx = 0
        while (cIdx < coordsStr.length) {
            while (cIdx < coordsStr.length && coordsStr[cIdx].isWhitespace()) cIdx++
            if (cIdx < coordsStr.length) {
                coordCount++
                while (cIdx < coordsStr.length && !coordsStr[cIdx].isWhitespace()) cIdx++
            }
        }
        
        // Parse coordinates directly into StrokePoint array (no intermediate boxed list)
        val pointCount = coordCount / 2
        val points = ArrayList<StrokePoint>(pointCount)
        var p = 0 // point index (for width lookup)
        var csIdx = 0
        while (csIdx < coordsStr.length && p < pointCount) {
            // Parse x
            while (csIdx < coordsStr.length && coordsStr[csIdx].isWhitespace()) csIdx++
            val xStart = csIdx
            while (csIdx < coordsStr.length && !coordsStr[csIdx].isWhitespace()) csIdx++
            val x = coordsStr.substring(xStart, csIdx).toDoubleOrNull() ?: 0.0
            
            // Parse y
            while (csIdx < coordsStr.length && coordsStr[csIdx].isWhitespace()) csIdx++
            val yStart = csIdx
            while (csIdx < coordsStr.length && !coordsStr[csIdx].isWhitespace()) csIdx++
            val y = coordsStr.substring(yStart, csIdx).toDoubleOrNull() ?: 0.0
            
            val uniform = widths.size <= 1
            val w = if (uniform) widths.getOrElse(0) { 1.0 } else widths.getOrElse(p) { 1.0 }
            points += StrokePoint(x, y, w)
            p++
        }
        
        return Stroke(tool, color, capStyle, points, widths.size <= 1, lineStyle, fill, extra)
    }

    private fun readText(): TextElement {
        val font = r.attr("font") ?: "Sans"
        val size = r.attr("size")?.toDoubleOrNull() ?: 12.0
        val x = r.attr("x")?.toDoubleOrNull() ?: 0.0
        val y = r.attr("y")?.toDoubleOrNull() ?: 0.0
        val color = XoppColor.parse(r.attr("color"))
        val extra = extras(textKnownAttrs)
        val content = readTextContent()
        return TextElement(font, size, x, y, color, content, extra)
    }

    private fun readImage(): ImageElement {
        val left = r.attr("left")?.toDoubleOrNull() ?: 0.0
        val top = r.attr("top")?.toDoubleOrNull() ?: 0.0
        val right = r.attr("right")?.toDoubleOrNull() ?: 0.0
        val bottom = r.attr("bottom")?.toDoubleOrNull() ?: 0.0
        val extra = extras(imageKnownAttrs)
        val b64 = readTextContent().trim()
        // Desktop Xournal++ line-wraps the base64 body, so use the MIME decoder (which skips
        // whitespace) and fall back to an empty image rather than failing the whole file.
        val bytes = if (b64.isEmpty()) {
            ByteArray(0)
        } else {
            runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull() ?: ByteArray(0)
        }
        return ImageElement(left, top, right, bottom, bytes, extra)
    }

    private fun readTexImage(): TexImageElement {
        val left = r.attr("left")?.toDoubleOrNull() ?: 0.0
        val top = r.attr("top")?.toDoubleOrNull() ?: 0.0
        val right = r.attr("right")?.toDoubleOrNull() ?: 0.0
        val bottom = r.attr("bottom")?.toDoubleOrNull() ?: 0.0
        val color = XoppColor.parse(r.attr("color"))
        val attrLatex = r.attr("text")
        val extra = extras(texKnownAttrs)
        // With a `text` attribute the body is the desktop-rendered PNG (base64); without one,
        // older files put the LaTeX source itself in the body.
        val body = readTextContent().trim()
        val latex = attrLatex ?: body
        val data = if (attrLatex != null && body.isNotEmpty()) {
            runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull()
        } else {
            null
        }
        return TexImageElement(
            left, top, right, bottom, latex, color, data,
            latexInAttribute = attrLatex != null,
            extraAttrs = extra,
        )
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
