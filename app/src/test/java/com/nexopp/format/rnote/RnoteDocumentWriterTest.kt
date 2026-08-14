package com.nexopp.format.rnote

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole `.rnote` write path end to end: `Document → writeRnote → open → parse → readDocument`.
 * This is the round trip the project's guiding principle is about — anything that survives the
 * mapping in both directions must come back out of the file unchanged.
 */
class RnoteDocumentWriterTest {

    /** A4 in pt. */
    private val a4Width = 595.28
    private val a4Height = 841.89

    private fun stroke(tool: Tool, color: Int, y: Double) = Stroke(
        tool = tool,
        color = color,
        capStyle = "round",
        points = listOf(
            StrokePoint(20.0, y, 1.2),
            StrokePoint(40.0, y + 10.0, 0.9),
            StrokePoint(60.0, y + 5.0, 0.6),
        ),
        uniformWidth = false,
    )

    private fun page(vararg layers: Layer) = Page(
        width = a4Width,
        height = a4Height,
        background = Background.Solid(0xFFFFFFF0.toInt(), "ruled"),
        layers = layers.toList(),
    )

    /** Write the document out and read it straight back, exactly as a save-then-open would. */
    private fun roundTrip(document: Document): Document {
        val bytes = ByteArrayOutputStream()
        writeRnote(document, bytes)
        val wrapper = RnoteContainer.open(ByteArrayInputStream(bytes.toByteArray()))
        return readDocument(RnoteSnapshot.parse(wrapper.snapshot)).document
    }

    @Test
    fun `pages, layers and stroke geometry survive a write and read`() {
        val source = Document(
            pages = listOf(
                page(
                    Layer(listOf(stroke(Tool.HIGHLIGHTER, 0x80FFFF00.toInt(), 40.0)), "highlighter"),
                    Layer(listOf(stroke(Tool.PEN, 0xFF3333CC.toInt(), 60.0)), "user_layer 0"),
                ),
                page(Layer(listOf(stroke(Tool.PEN, 0xFFCC0000.toInt(), 100.0)), "user_layer 0")),
            ),
        )
        val back = roundTrip(source)

        assertEquals(2, back.pages.size)
        assertEquals(listOf("highlighter", "user_layer 0"), back.pages[0].layers.map { it.name })
        // Every page gets the same stack, so page 2's ink is on the same layer index as page 1's.
        assertEquals(listOf("highlighter", "user_layer 0"), back.pages[1].layers.map { it.name })

        val highlighter = back.pages[0].layers[0].elements.single() as Stroke
        assertEquals(Tool.HIGHLIGHTER, highlighter.tool)
        assertEquals(0x80FFFF00.toInt(), highlighter.color)

        val ink = back.pages[0].layers[1].elements.single() as Stroke
        val expected = stroke(Tool.PEN, 0xFF3333CC.toInt(), 60.0)
        assertEquals(expected.points.size, ink.points.size)
        for ((want, got) in expected.points.zip(ink.points)) {
            assertEquals(want.x, got.x, 1e-6)
            assertEquals(want.y, got.y, 1e-6)
            assertEquals(want.width, got.width, 1e-6)
        }
        assertEquals(0xFFCC0000.toInt(), (back.pages[1].layers[1].elements.single() as Stroke).color)
    }

    @Test
    fun `a text box and a picture survive with their placement`() {
        val png = RawImageCodec.encodePng(ByteArray(2 * 2 * 4) { 0xFF.toByte() }, 2, 2)
        val source = Document(
            pages = listOf(
                page(
                    Layer(listOf(ImageElement(100.0, 100.0, 150.0, 150.0, png)), "image"),
                    Layer(listOf(TextElement("Serif Bold", 14.0, 72.0, 72.0, 0xFF101010.toInt(), "hi")), "user_layer 0"),
                ),
            ),
        )
        val back = roundTrip(source)

        val image = back.pages[0].layers.flatMap { it.elements }.filterIsInstance<ImageElement>().single()
        assertEquals(100.0, image.left, 1e-3)
        assertEquals(150.0, image.bottom, 1e-3)
        assertTrue(RawImageCodec.decodePng(image.data) != null)

        val text = back.pages[0].layers.flatMap { it.elements }.filterIsInstance<TextElement>().single()
        assertEquals("hi", text.content)
        assertEquals("Serif Bold", text.font)
        assertEquals(14.0, text.size, 1e-6)
        assertEquals(72.0, text.x, 1e-6)
        assertEquals(72.0, text.y, 1e-6)
    }

    @Test
    fun `the page background crosses as its ruling style`() {
        val back = roundTrip(
            Document(pages = listOf(page(Layer(listOf(stroke(Tool.PEN, 0xFF000000.toInt(), 20.0)))))),
        )
        assertEquals(Background.Solid(0xFFFFFFF0.toInt(), "ruled"), back.pages[0].background)
    }

    @Test
    fun `an empty document writes a file that reopens as one blank page`() {
        val back = roundTrip(Document())
        assertEquals(1, back.pages.size)
        assertEquals(listOf("user_layer 0"), back.pages[0].layers.map { it.name })
        assertTrue(back.pages[0].layers.single().elements.isEmpty())
    }
}
