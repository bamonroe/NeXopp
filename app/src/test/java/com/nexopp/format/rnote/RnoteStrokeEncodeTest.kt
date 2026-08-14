package com.nexopp.format.rnote

import com.nexopp.format.json.JsonValue
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The element encoders, checked the only way that matters for round-trip safety: encode, then read
 * the result straight back through the import converters in `RnoteStrokeConvert.kt` and assert the
 * model survived. A shape mistake in either direction fails here.
 */
class RnoteStrokeEncodeTest {

    /** Wrap an encoded body as the slot the import side would have read it out of. */
    private fun slot(kind: String, body: JsonValue, layer: String = "user_layer") =
        RnoteStroke(1, kind, body, 1L, layer, 0)

    private fun penStroke(
        points: List<StrokePoint>,
        uniform: Boolean = false,
        lineStyle: LineStyle = LineStyle.PLAIN,
        capStyle: String? = "round",
        fill: Int? = null,
    ) = Stroke(Tool.PEN, 0xFF3366CC.toInt(), capStyle, points, uniform, lineStyle, fill)

    @Test
    fun `a pressure stroke round-trips its geometry and per-point widths`() {
        val source = penStroke(
            listOf(
                StrokePoint(100.0, 100.0, 1.2),
                StrokePoint(110.0, 105.0, 0.98),
                StrokePoint(120.0, 108.0, 0.76),
            ),
        )
        val back = brushStrokeToStroke(slot("brushstroke", strokeToBrushStroke(source)!!))!!
        assertFalse(back.uniformWidth)
        assertEquals(source.points.size, back.points.size)
        for ((expected, actual) in source.points.zip(back.points)) {
            assertEquals(expected.x, actual.x, 1e-6)
            assertEquals(expected.y, actual.y, 1e-6)
            assertEquals(expected.width, actual.width, 1e-6)
        }
        assertEquals(source.color, back.color)
    }

    @Test
    fun `a constant-width stroke comes back uniform`() {
        val source = penStroke(
            listOf(StrokePoint(10.0, 10.0, 1.5), StrokePoint(40.0, 20.0, 1.5)),
            uniform = true,
        )
        val back = brushStrokeToStroke(slot("brushstroke", strokeToBrushStroke(source)!!))!!
        assertTrue(back.uniformWidth)
        for (point in back.points) assertEquals(1.5, point.width, 1e-6)
    }

    @Test
    fun `pressure_curve is always linear so widths are not re-curved on reopen`() {
        val body = strokeToBrushStroke(penStroke(listOf(StrokePoint(0.0, 0.0, 1.0))))!!
        assertEquals("linear", body.path("style", "smooth", "pressure_curve")?.str())
    }

    @Test
    fun `the pen pattern, cap and fill cross over`() {
        for ((style, name) in listOf(
            LineStyle.PLAIN to "solid",
            LineStyle.DOTTED to "dotted",
            LineStyle.DASHED to "dashed_equidistant",
            LineStyle.DASH_DOT to "dashed_equidistant",
        )) {
            val source = penStroke(listOf(StrokePoint(0.0, 0.0, 1.0)), lineStyle = style, fill = 0x80)
            val body = strokeToBrushStroke(source)!!
            assertEquals(name, body.path("style", "smooth", "line_style")?.str())
            val back = brushStrokeToStroke(slot("brushstroke", body))!!
            // `dashdot` has no Rnote spelling of its own, so it comes back as a plain dash.
            assertEquals(if (style == LineStyle.DASH_DOT) LineStyle.DASHED else style, back.lineStyle)
            assertEquals(0x80, back.fill)
        }
    }

    @Test
    fun `only round survives as a cap, the other two collapse onto straight`() {
        for ((cap, expected) in listOf("round" to "round", null to "round", "butt" to "butt", "square" to "butt")) {
            val body = strokeToBrushStroke(penStroke(listOf(StrokePoint(0.0, 0.0, 1.0)), capStyle = cap))!!
            assertEquals(expected, brushStrokeToStroke(slot("brushstroke", body))!!.capStyle)
        }
    }

    @Test
    fun `a zero-width stroke encodes without dividing by zero`() {
        val body = strokeToBrushStroke(penStroke(listOf(StrokePoint(0.0, 0.0, 0.0), StrokePoint(1.0, 1.0, 0.0))))!!
        assertEquals(0.0, body.path("style", "smooth", "stroke_width")?.num()!!, 0.0)
        assertEquals(1.0, body.path("path", "start", "pressure")?.num()!!, 0.0)
        assertTrue(brushStrokeToStroke(slot("brushstroke", body))!!.points.all { it.width == 0.0 })
    }

    @Test
    fun `a stroke with no points is skipped, not written as a path with no start`() {
        assertNull(strokeToBrushStroke(penStroke(emptyList())))
    }

    @Test
    fun `a text box round-trips its content, font, size, colour and position`() {
        for (font in listOf("Sans", "Serif Bold", "Serif Italic", "Sans Bold Italic")) {
            val source = TextElement(font, 12.0, 72.0, 72.0, 0xFF101010.toInt(), "a & b < c > d")
            val back = textStrokeToText(slot("textstroke", textToTextStroke(source)))!!
            assertEquals(font, back.font)
            assertEquals(source.content, back.content)
            assertEquals(source.size, back.size, 1e-6)
            assertEquals(source.x, back.x, 1e-6)
            assertEquals(source.y, back.y, 1e-6)
            assertEquals(source.color, back.color)
        }
    }

    @Test
    fun `the affine is column-major, so the translation sits at indices 6 and 7`() {
        val body = textToTextStroke(TextElement("Sans", 12.0, 72.0, 36.0, 0, ""))
        val affine = body.path("transform", "affine")?.arr()!!.map { it.num() }
        assertEquals(listOf(1.0, 0.0, 0.0, -0.0, 1.0, 0.0, 96.0, 48.0, 1.0), affine)
    }

    @Test
    fun `an image round-trips its pixels and its pt box`() {
        // Opaque, so premultiplying on the way out is the identity and the bytes compare exactly.
        val pixels = ByteArray(2 * 2 * 4) { if (it % 4 == 3) 0xFF.toByte() else (it * 37 % 251).toByte() }
        val source = ImageElement(100.0, 100.0, 150.0, 150.0, RawImageCodec.encodePng(pixels, 2, 2))
        val back = bitmapImageToImage(slot("bitmapimage", imageToBitmapImage(source)!!))!!
        assertEquals(source.left, back.left, 1e-6)
        assertEquals(source.top, back.top, 1e-6)
        assertEquals(source.right, back.right, 1e-6)
        assertEquals(source.bottom, back.bottom, 1e-6)
        assertArrayEquals(pixels, RawImageCodec.decodePng(back.data)!!.rgba)
    }

    @Test
    fun `a LaTeX box exports the rendering it already carries`() {
        val png = RawImageCodec.encodePng(ByteArray(4) { 0xFF.toByte() }, 1, 1)
        val tex = TexImageElement(0.0, 0.0, 10.0, 10.0, "x^2", 0, png)
        assertEquals(1, texImageToBitmapImage(tex)?.path("image", "pixel_width")?.num()?.toInt())
        // Nothing rendered and nothing decodable are both "skip and report", never a broken stroke.
        assertNull(texImageToBitmapImage(tex.copy(data = null)))
        assertNull(imageToBitmapImage(ImageElement(0.0, 0.0, 1.0, 1.0, JPEG_HEADER)))
    }

    private companion object {
        /** A JPEG's SOI + APP0 marker: decodable by no code here, so it must encode to null. */
        val JPEG_HEADER = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        )
    }
}
