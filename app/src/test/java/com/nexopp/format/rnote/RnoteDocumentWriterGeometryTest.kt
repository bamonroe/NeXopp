package com.nexopp.format.rnote

import com.nexopp.format.Xopp
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export half of the canvas ↔ pages rule, checked against the same fixture the import half uses:
 * `fixtures/backgrounds.xopp` is five A4 pages, and `rnote-cli` stacked its twin's pages at
 * `i x 1122.52` px with no gap. Laying our pages back out must land on those same offsets.
 */
class RnoteDocumentWriterGeometryTest {

    /** The five-A4-page fixture, read through the `.xopp` reader. */
    private val backgrounds: Document = javaClass.classLoader!!
        .getResourceAsStream("fixtures/backgrounds.xopp")
        ?.use { Xopp.parseXml(GZIPInputStream(it).readBytes().decodeToString()) }
        ?: error("missing fixture fixtures/backgrounds.xopp")

    @Test
    fun `an A4 page exports an A4 canvas format`() {
        val format = canvasFormat(backgrounds)
        assertEquals(793.701, format.width, 0.01)
        assertEquals(1122.52, format.height, 0.01)
        assertEquals(96.0, format.dpi, 1e-9)
        assertEquals("portrait", format.orientation)
    }

    @Test
    fun `a wide page exports as landscape`() {
        val landscape = Document(pages = listOf(page(width = 841.89, height = 595.28)))
        assertEquals("landscape", canvasFormat(landscape).orientation)
    }

    @Test
    fun `an empty document falls back to A4`() {
        val format = canvasFormat(Document())
        assertEquals(793.701, format.width, 0.01)
        assertEquals(1122.52, format.height, 0.01)
    }

    @Test
    fun `the fixture's pages stack exactly where rnote-cli put them`() {
        val origins = pageOriginsPx(backgrounds)
        val expected = listOf(0.0, 1122.52, 2245.04, 3367.56, 4490.08)
        assertEquals(expected.size, origins.size)
        for (i in expected.indices) {
            assertEquals("page $i origin", expected[i], origins[i], 0.01)
        }
    }

    @Test
    fun `the canvas extent covers the widest page and the whole stack`() {
        val extent = canvasExtent(backgrounds)
        assertEquals(0.0, extent.minX, 1e-9)
        assertEquals(0.0, extent.minY, 1e-9)
        assertEquals(793.701, extent.maxX, 0.01)
        assertEquals(5 * 1122.52, extent.maxY, 0.01)
    }

    @Test
    fun `the fixture's pages are all the same size`() {
        assertFalse(hasMixedPageSizes(backgrounds))
        assertFalse(hasMixedPageSizes(Document()))
    }

    @Test
    fun `a page that differs from page 1 is mixed`() {
        val mixed = Document(
            pages = listOf(
                page(width = 595.28, height = 841.89),
                page(width = 595.28, height = 1000.0),
            ),
        )
        assertTrue(hasMixedPageSizes(mixed))
    }

    @Test
    fun `a highlighter stroke and an image ignore their layer`() {
        val named = Layer(elements = emptyList(), name = "Layer 1")
        assertEquals("highlighter" to null, slotFor(stroke(Tool.HIGHLIGHTER), named, 3))
        assertEquals("image" to null, slotFor(image(), named, 3))
        assertEquals("highlighter" to null, slotFor(stroke(Tool.HIGHLIGHTER), rnoteLayer("document"), 0))
        assertEquals("image" to null, slotFor(image(), rnoteLayer("user_layer 2"), 0))
    }

    @Test
    fun `a layer named after an rnote slot is taken at its word`() {
        assertEquals("document" to null, slotFor(stroke(Tool.PEN), rnoteLayer("document"), 4))
        assertEquals("user_layer" to 2, slotFor(stroke(Tool.PEN), rnoteLayer("user_layer 2"), 4))
    }

    @Test
    fun `any other layer becomes its own pen layer`() {
        assertEquals("user_layer" to 0, slotFor(stroke(Tool.PEN), Layer(emptyList()), 0))
        assertEquals("user_layer" to 2, slotFor(stroke(Tool.PEN), Layer(emptyList(), name = "Ink"), 2))
        assertEquals("user_layer" to 1, slotFor(stroke(Tool.PEN), rnoteLayer("user_layer x"), 1))
    }

    // ---- fixtures ------------------------------------------------------------------------

    private fun page(width: Double, height: Double) =
        Page(
            width = width,
            height = height,
            background = Background.Solid(color = 0xFFFFFFFF.toInt(), style = "plain"),
            layers = emptyList(),
        )

    private fun rnoteLayer(name: String) = Layer(elements = emptyList(), name = name)

    private fun stroke(tool: Tool) = Stroke(
        tool = tool,
        color = 0xFF000000.toInt(),
        capStyle = null,
        points = listOf(StrokePoint(0.0, 0.0, 1.0)),
        uniformWidth = true,
    )

    private fun image() =
        ImageElement(left = 0.0, top = 0.0, right = 10.0, bottom = 10.0, data = ByteArray(0))
}
