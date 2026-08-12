package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Page
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import com.nexopp.format.xml.XmlPullReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.StringWriter
import java.util.Base64
import org.junit.Test

/**
 * Pure-JVM cover for [SvgExporter]: build a page in code, export it, and read the markup back with
 * [XmlPullReader] — which doubles as the well-formedness check.
 */
class SvgExporterTest {

    private fun page(
        vararg elements: Element,
        style: String = "plain",
        background: Background = Background.Solid(0xFFFFFFFF.toInt(), style),
    ) = Page(
        width = 100.0,
        height = 200.0,
        background = background,
        layers = listOf(Layer(elements.toList())),
    )

    private fun export(page: Page): String =
        StringWriter().also { SvgExporter().export(page, it) }.toString()

    /** Every start tag with its attributes, in document order; throws if the markup is malformed. */
    private fun elements(svg: String): List<Pair<String, Map<String, String>>> {
        val r = XmlPullReader(svg)
        val out = ArrayList<Pair<String, Map<String, String>>>()
        val open = ArrayDeque<String>()
        while (true) {
            when (r.next()) {
                XmlPullReader.Event.START -> {
                    out += r.name to LinkedHashMap(r.attributes())
                    open.addLast(r.name)
                }
                XmlPullReader.Event.END -> assertEquals("unbalanced end tag", open.removeLast(), r.name)
                XmlPullReader.Event.TEXT -> Unit
                XmlPullReader.Event.EOF -> {
                    assertTrue("unclosed elements: $open", open.isEmpty())
                    return out
                }
            }
        }
    }

    private fun paths(svg: String) = elements(svg).filter { it.first == "path" }.map { it.second }

    private fun pen(vararg pts: StrokePoint, style: LineStyle = LineStyle.PLAIN, fill: Int? = null) =
        Stroke(
            tool = Tool.PEN,
            color = 0xFF112233.toInt(),
            capStyle = null,
            points = pts.toList(),
            uniformWidth = true,
            lineStyle = style,
            fill = fill,
        )

    @Test fun rootCarriesSvgNamespaceSizeAndViewBox() {
        val (name, attrs) = elements(export(page())).first()
        assertEquals("svg", name)
        assertEquals("http://www.w3.org/2000/svg", attrs["xmlns"])
        assertEquals("100pt", attrs["width"])
        assertEquals("200pt", attrs["height"])
        assertEquals("0 0 100 200", attrs["viewBox"])
    }

    @Test fun plainStrokeIsOnePathWithItsGeometryAndColour() {
        val svg = export(page(pen(StrokePoint(10.0, 20.5, 2.0), StrokePoint(30.0, 40.0, 2.0))))
        val path = paths(svg).single()
        assertEquals("M 10 20.5 L 30 40", path["d"])
        assertEquals("#112233", path["stroke"])
        assertEquals("2", path["stroke-width"])
        assertEquals("none", path["fill"])
        assertEquals("round", path["stroke-linecap"])
        // Opaque ink needs no opacity attribute at all.
        assertNull(path["stroke-opacity"])
    }

    @Test fun pressureStrokeIsOnePathPerSegmentWithTheMeanWidth() {
        val svg = export(
            page(
                pen(
                    StrokePoint(0.0, 0.0, 1.0),
                    StrokePoint(10.0, 0.0, 3.0),
                    StrokePoint(20.0, 0.0, 5.0),
                ),
            ),
        )
        val widths = paths(svg).map { it["stroke-width"] }
        assertEquals(listOf("2", "4"), widths)
    }

    @Test fun highlighterCarriesStrokeOpacity() {
        val hl = Stroke(
            tool = Tool.HIGHLIGHTER,
            color = 0xFFFFFF00.toInt(),
            capStyle = null,
            points = listOf(StrokePoint(0.0, 0.0, 8.0), StrokePoint(50.0, 50.0, 8.0)),
            uniformWidth = true,
        )
        val path = paths(export(page(hl))).single()
        assertEquals("#ffff00", path["stroke"])
        assertEquals("0.502", path["stroke-opacity"]) // forced 0x80 alpha, as on screen
        assertEquals("8", path["stroke-width"])
    }

    @Test fun dashedStrokeCarriesTheSharedDashPattern() {
        val svg = export(
            page(
                pen(
                    StrokePoint(0.0, 0.0, 2.0),
                    StrokePoint(40.0, 0.0, 2.0),
                    style = LineStyle.DASHED,
                ),
            ),
        )
        val path = paths(svg).single()
        // StrokePainter.dashIntervalsPt(DASHED, 2.0) == [8, 6] — reused, not re-derived.
        assertEquals("8,6", path["stroke-dasharray"])
    }

    @Test fun filledStrokeGetsAClosedFillPathUnderTheOutline() {
        val svg = export(
            page(
                pen(
                    StrokePoint(0.0, 0.0, 1.0),
                    StrokePoint(10.0, 0.0, 1.0),
                    StrokePoint(10.0, 10.0, 1.0),
                    fill = 0x80,
                ),
            ),
        )
        val fill = paths(svg).first()
        assertEquals("M 0 0 L 10 0 L 10 10 Z", fill["d"])
        assertEquals("#112233", fill["fill"])
        assertEquals("0.502", fill["fill-opacity"])
        assertEquals("none", fill["stroke"])
    }

    @Test fun solidBackgroundIsASheetRectAndItsRuling() {
        val els = elements(export(page(style = "ruled")))
        val rect = els.first { it.first == "rect" }.second
        assertEquals("100", rect["width"])
        assertEquals("200", rect["height"])
        assertEquals("#ffffff", rect["fill"])
        val lines = els.filter { it.first == "line" }.map { it.second }
        // Interior rules every 24pt down a 200pt page, plus the ruled margin line.
        assertEquals(BackgroundGrid.lines(200.0, BackgroundGrid.RULE_SPACING_PT).size + 1, lines.size)
        assertEquals("72", lines.last()["x1"])
    }

    @Test fun pdfBackgroundGetsThePlainSheetOnly() {
        val els = elements(export(page(background = Background.Pdf("doc.pdf", 0, "absolute"))))
        assertEquals(1, els.count { it.first == "rect" })
        assertTrue(els.none { it.first == "line" })
    }

    @Test fun textBoxIsOneTspanPerLineUnderATextElement() {
        val text = TextElement("Serif Bold", 12.0, 5.0, 7.0, 0xFF204060.toInt(), "first\nsecond")
        val svg = export(page(text))
        val els = elements(svg)
        val t = els.single { it.first == "text" }.second
        assertEquals("5", t["x"])
        assertEquals("7", t["y"])
        assertEquals("serif", t["font-family"])
        assertEquals("12", t["font-size"])
        assertEquals("bold", t["font-weight"])
        assertNull(t["font-style"])
        assertEquals("#204060", t["fill"])
        val spans = els.filter { it.first == "tspan" }.map { it.second }
        assertEquals(2, spans.size)
        // First line drops one ascent below the box top; the next advances by a line height.
        assertEquals(listOf("5", "5"), spans.map { it["x"] })
        assertEquals("9", spans[0]["dy"])
        assertEquals("14.4", spans[1]["dy"])
        assertTrue(svg.contains(">first<"))
        assertTrue(svg.contains(">second<"))
    }

    @Test fun imageEmbedsItsBytesAsADataUriAndDeclaresXlink() {
        val img = ImageElement(10.0, 20.0, 40.0, 60.0, PNG)
        val svg = export(page(img))
        val els = elements(svg)
        assertEquals(
            "http://www.w3.org/1999/xlink",
            els.first().second["xmlns:xlink"],
        )
        val image = els.single { it.first == "image" }.second
        assertEquals("10", image["x"])
        assertEquals("20", image["y"])
        assertEquals("30", image["width"])
        assertEquals("40", image["height"])
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG),
            image["xlink:href"],
        )
    }

    @Test fun aPageWithoutImagesDoesNotDeclareXlink() {
        val svg = export(page(pen(StrokePoint(0.0, 0.0, 1.0), StrokePoint(5.0, 5.0, 1.0))))
        assertNull(elements(svg).first().second["xmlns:xlink"])
    }

    @Test fun texImageExportsItsRenderedPngAsAnImage() {
        val tex = TexImageElement(1.0, 2.0, 11.0, 12.0, "x^2", 0xFF000000.toInt(), data = PNG)
        val image = elements(export(page(tex))).single { it.first == "image" }.second
        assertEquals("10", image["width"])
        assertTrue(image["xlink:href"]!!.startsWith("data:image/png;base64,"))
    }

    @Test fun texImageWithoutARenderedPngIsSkipped() {
        val tex = TexImageElement(1.0, 2.0, 11.0, 12.0, "x^2", 0xFF000000.toInt(), data = null)
        assertTrue(elements(export(page(tex))).none { it.first == "image" })
    }

    @Test fun rawElementProducesNoOutput() {
        val raw = RawElement("vendorthing", mapOf("a" to "1"), "body")
        val els = elements(export(page(raw)))
        assertTrue(els.none { it.first == "vendorthing" || it.first == "image" || it.first == "text" })
    }

    @Test fun degenerateStrokeIsDropped() {
        assertTrue(paths(export(page(pen(StrokePoint(1.0, 1.0, 1.0))))).isEmpty())
    }

    private companion object {
        /** A PNG signature plus a byte of payload — enough for the media-type sniff and base64. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
    }
}
