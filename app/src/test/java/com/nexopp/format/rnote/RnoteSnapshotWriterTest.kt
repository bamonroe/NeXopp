package com.nexopp.format.rnote

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page stack → canvas assembler, checked by parsing its own output back with
 * [RnoteSnapshot.parse]: if the slot arrays, the chrono order or the layer names are wrong, the
 * intermediate model the importer reads says so.
 */
class RnoteSnapshotWriterTest {

    /** A4 in pt — the size `canvasFormat` converts back to Rnote's 793.701 x 1122.52 px. */
    private val a4Width = 595.28
    private val a4Height = 841.89

    private fun stroke(tool: Tool = Tool.PEN, y: Double = 10.0) = Stroke(
        tool = tool,
        color = 0xFF000000.toInt(),
        capStyle = "round",
        points = listOf(StrokePoint(10.0, y, 1.5), StrokePoint(20.0, y + 5.0, 1.5)),
        uniformWidth = true,
    )

    private fun page(vararg layers: Layer) = Page(
        width = a4Width,
        height = a4Height,
        background = Background.Solid(0xFFFFFFFF.toInt(), "graph"),
        layers = layers.toList(),
    )

    private fun layer(name: String?, vararg elements: Element) = Layer(elements.toList(), name)

    private fun parse(document: Document): RnoteSnapshot = RnoteSnapshot.parse(writeSnapshot(document))

    @Test
    fun `the canvas is a fixed-size page stack with page 1's format`() {
        val document = Document(pages = listOf(page(layer(null, stroke())), page(layer(null, stroke()))))
        val snapshot = parse(document)
        assertEquals("fixed_size", snapshot.layout)
        assertEquals(793.701, snapshot.format.width, 1e-2)
        assertEquals(1122.52, snapshot.format.height, 1e-2)
        assertEquals(96.0, snapshot.format.dpi, 0.0)
        assertEquals("portrait", snapshot.format.orientation)
        assertEquals(0.0, snapshot.docX, 0.0)
        assertEquals(0.0, snapshot.docY, 0.0)
        assertEquals(793.701, snapshot.docWidth, 1e-2)
        assertEquals(2 * 1122.52, snapshot.docHeight, 1e-2)
    }

    @Test
    fun `page 2's strokes sit one page height down the canvas`() {
        val document = Document(pages = listOf(page(layer(null, stroke())), page(layer(null, stroke()))))
        val ys = parse(document).strokes.map { it.body.path("path", "start", "pos")?.arr()!![1].num()!! }
        assertEquals(2, ys.size)
        assertEquals(ptToPx(10.0), ys[0], 1e-6)
        assertEquals(ptToPx(10.0) + 1122.52, ys[1], 1e-2)
    }

    @Test
    fun `slot zero is null and t counts from one in painters order`() {
        val document = Document(
            pages = listOf(page(layer(null, stroke(), stroke()), layer(null, stroke()))),
        )
        val snapshot = writeSnapshot(document)
        val slots = snapshot.obj("stroke_components")?.arr()!!
        assertEquals(4, slots.size)
        assertTrue(slots[0].obj("value")!!.isNull())
        assertEquals(listOf(1L, 2L, 3L), RnoteSnapshot.parse(snapshot).strokes.map { it.z })
        assertEquals(4.0, snapshot.obj("chrono_counter")?.num()!!, 0.0)
    }

    @Test
    fun `each element lands on the rnote slot its kind and layer choose`() {
        val png = RawImageCodec.encodePng(ByteArray(4) { 0xFF.toByte() }, 1, 1)
        val document = Document(
            pages = listOf(
                page(
                    layer("document", stroke()),
                    layer(null, stroke(Tool.HIGHLIGHTER), ImageElement(0.0, 0.0, 10.0, 10.0, png)),
                    layer(null, TextElement("Sans", 12.0, 5.0, 5.0, 0xFF000000.toInt(), "hi")),
                ),
            ),
        )
        val slots = parse(document).strokes.map { layerSlotName(it) to it.kind }
        assertEquals(
            listOf(
                "document" to "brushstroke",
                "highlighter" to "brushstroke",
                "image" to "bitmapimage",
                "user_layer 2" to "textstroke",
            ),
            slots,
        )
    }

    @Test
    fun `an eraser stroke, a raw element and an empty stroke are skipped`() {
        val document = Document(
            pages = listOf(
                page(
                    layer(
                        null,
                        stroke(Tool.ERASER),
                        RawElement("vendor:thing"),
                        stroke().copy(points = emptyList()),
                        stroke(),
                    ),
                ),
            ),
        )
        val strokes = parse(document).strokes
        assertEquals(1, strokes.size)
        assertEquals("brushstroke", strokes[0].kind)
    }

    @Test
    fun `the background comes from page 1 and carries the pitch Xournal renders at`() {
        val document = Document(
            pages = listOf(
                page(layer(null, stroke())),
                Page(a4Width, a4Height, Background.Solid(0xFF00FF00.toInt(), "ruled"), listOf()),
            ),
        )
        val background = parse(document).background
        assertEquals("grid", background.pattern)
        assertEquals(18.893, background.patternWidth, 1e-3)
        assertEquals(1.0, background.color.r, 1e-6)
    }

    @Test
    fun `an empty document still writes a valid one-page canvas`() {
        val snapshot = parse(Document())
        assertEquals("fixed_size", snapshot.layout)
        assertEquals("none", snapshot.background.pattern)
        assertEquals(1122.52, snapshot.docHeight, 1e-2)
        assertTrue(snapshot.strokes.isEmpty())
    }
}
