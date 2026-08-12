package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Tool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `brushstroke` → `Stroke` and `textstroke` → `TextElement` conversions against `plain.rnote`
 * and `text-image.rnote`, the ground-truth twins of `plain.xopp` and `text-image.xopp` (see
 * `docs/architecture.md`, "The stroke & pen mapping"), plus hand-built payloads for the shapes no
 * fixture covers.
 */
class RnoteStrokeConvertTest {

    private fun strokes(name: String): List<RnoteStroke> {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")
        return RnoteSnapshot.parse(wrapper.snapshot).strokes
    }

    private fun parseStroke(json: String, layer: String = "user_layer"): RnoteStroke =
        RnoteStroke(1, "brushstroke", JsonReader(json).parse(), 1L, layer, 0)

    @Test
    fun `the pressure stroke keeps a width per vertex`() {
        val stroke = brushStrokeToStroke(strokes("plain")[0])!!
        assertEquals(Tool.PEN, stroke.tool)
        assertFalse(stroke.uniformWidth)
        assertEquals(3, stroke.points.size)
        assertEquals(100.0, stroke.points[0].x, 1e-2)
        assertEquals(100.0, stroke.points[0].y, 1e-2)
        for ((point, expected) in stroke.points.zip(listOf(1.2, 0.98, 0.76))) {
            assertEquals(expected, point.width, 1e-2)
        }
        assertEquals(0xFF3333CC.toInt(), stroke.color)
    }

    @Test
    fun `the constant-width stroke is uniform`() {
        val stroke = brushStrokeToStroke(strokes("plain")[1])!!
        assertEquals(Tool.PEN, stroke.tool)
        assertTrue(stroke.uniformWidth)
        for (point in stroke.points) assertEquals(1.5, point.width, 1e-2)
        assertEquals(0xFFFF0000.toInt(), stroke.color)
    }

    @Test
    fun `the highlighter stroke keeps its tool, width and half alpha`() {
        val stroke = brushStrokeToStroke(strokes("plain")[2])!!
        assertEquals(Tool.HIGHLIGHTER, stroke.tool)
        assertTrue(stroke.uniformWidth)
        for (point in stroke.points) assertEquals(8.5, point.width, 1e-2)
        assertEquals(0x80FFFF00.toInt(), stroke.color)
        assertEquals(LineStyle.PLAIN, stroke.lineStyle)
        assertNull(stroke.fill)
        assertNull(stroke.capStyle)
    }

    @Test
    fun `another stroke kind converts to null`() {
        val other = RnoteStroke(1, "shapestroke", JsonReader("""{"shape":"line"}""").parse(), 1L, "user_layer", 0)
        assertNull(brushStrokeToStroke(other))
    }

    @Test
    fun `a textured brush reads its width and colour out of the textured tag`() {
        val stroke = brushStrokeToStroke(
            parseStroke(
                """
                {"path":{"start":{"pos":[0.0,0.0],"pressure":1.0},
                  "segments":[{"lineto":{"end":{"pos":[4.0,0.0],"pressure":1.0}}}]},
                 "style":{"textured":{"stroke_width":4.0,
                   "stroke_color":{"r":0.0,"g":0.0,"b":1.0,"a":1.0}}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(3.0, stroke.points[0].width, 1e-9)
        assertEquals(0xFF0000FF.toInt(), stroke.color)
    }

    @Test
    fun `a fill colour collapses to its alpha and a dashed line style maps across`() {
        val stroke = brushStrokeToStroke(
            parseStroke(
                """
                {"path":{"start":{"pos":[0.0,0.0],"pressure":1.0}},
                 "style":{"smooth":{"line_style":"dashed",
                   "fill_color":{"r":1.0,"g":1.0,"b":1.0,"a":0.5}}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(LineStyle.DASHED, stroke.lineStyle)
        assertEquals(128, stroke.fill)
        // No stroke_width and no stroke_color: the 2.0 px default in opaque black.
        assertEquals(1.5, stroke.points[0].width, 1e-9)
        assertEquals(0xFF000000.toInt(), stroke.color)
    }

    @Test
    fun `the textstroke keeps its content, font, size, colour and position`() {
        val text = textStrokeToText(strokes("text-image").single { it.kind == "textstroke" })!!
        assertEquals("a & b < c > d", text.content)
        assertEquals("Sans", text.font)
        assertEquals(12.0, text.size, 1e-2)
        assertEquals(0xFF101010.toInt(), text.color)
        // The affine's translation is 96 px in both axes — 72 pt, matching the .xopp twin.
        assertEquals(72.0, text.x, 1e-2)
        assertEquals(72.0, text.y, 1e-2)
        assertTrue(text.extraAttrs.isEmpty())
    }

    @Test
    fun `a brushstroke converts to no text and a textstroke to no stroke`() {
        val textstroke = strokes("text-image").single { it.kind == "textstroke" }
        assertNull(textStrokeToText(strokes("plain")[0]))
        assertNull(brushStrokeToStroke(textstroke))
    }

    @Test
    fun `a textstroke with no style or transform falls back to Sans 12pt black at the origin`() {
        val text = textStrokeToText(
            RnoteStroke(1, "textstroke", JsonReader("""{"text":"hi"}""").parse(), 1L, "user_layer", 0),
        )!!
        assertEquals("hi", text.content)
        assertEquals("Sans", text.font)
        assertEquals(12.0, text.size, 1e-9)
        assertEquals(0xFF000000.toInt(), text.color)
        assertEquals(0.0, text.x, 1e-9)
        assertEquals(0.0, text.y, 1e-9)
    }

    @Test
    fun `a textstroke with no text is rejected by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            textStrokeToText(
                RnoteStroke(1, "textstroke", JsonReader("""{"text_style":{}}""").parse(), 1L, "user_layer", 0),
            )
        }
        assertEquals("missing textstroke.text", error.message)
    }

    @Test
    fun `the bitmapimage keeps its pt box and comes back as a png`() {
        val stroke = strokes("text-image").first { it.kind == "bitmapimage" }
        val image = bitmapImageToImage(stroke)!!
        assertEquals(100.0, image.left, 1e-2)
        assertEquals(100.0, image.top, 1e-2)
        assertEquals(150.0, image.right, 1e-2)
        assertEquals(150.0, image.bottom, 1e-2)
        assertArrayEquals(
            RawImageCodec.PNG_SIGNATURE,
            image.data.copyOfRange(0, RawImageCodec.PNG_SIGNATURE.size),
        )
    }

    @Test
    fun `a stroke of another kind is not an image`() {
        assertNull(bitmapImageToImage(strokes("text-image").first { it.kind == "textstroke" }))
    }

    @Test
    fun `a bitmapimage with no rectangle is rejected by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            bitmapImageToImage(
                RnoteStroke(
                    1,
                    "bitmapimage",
                    JsonReader(
                        """{"image":{"data":"/wAA/w==","pixel_width":1,"pixel_height":1}}""",
                    ).parse(),
                    1L,
                    "image",
                    0,
                ),
            )
        }
        assertEquals("missing rectangle.transform.affine", error.message)
    }

    private fun parseShape(json: String): RnoteStroke =
        RnoteStroke(1, "shapestroke", JsonReader(json).parse(), 1L, "user_layer", 0)

    @Test
    fun `a line shape flattens to its two endpoints at a constant width`() {
        val stroke = shapeStrokeToStroke(
            parseShape(
                """
                {"shape":{"line":{"start":[0.0,0.0],"end":[96.0,48.0]}},
                 "style":{"smooth":{"stroke_width":4.0,
                   "stroke_color":{"r":0.0,"g":0.0,"b":1.0,"a":1.0}}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(Tool.PEN, stroke.tool)
        assertTrue(stroke.uniformWidth)
        assertEquals(2, stroke.points.size)
        assertEquals(0.0, stroke.points[0].x, 1e-9)
        assertEquals(0.0, stroke.points[0].y, 1e-9)
        assertEquals(72.0, stroke.points[1].x, 1e-9)
        assertEquals(36.0, stroke.points[1].y, 1e-9)
        for (point in stroke.points) assertEquals(3.0, point.width, 1e-9)
        assertEquals(0xFF0000FF.toInt(), stroke.color)
    }

    @Test
    fun `a rect shape flattens to five corners that close on the first`() {
        val stroke = shapeStrokeToStroke(
            parseShape(
                """
                {"shape":{"rect":{"cuboid":{"half_extents":[24.0,12.0]},
                   "transform":{"affine":[1,0,0,0,1,0,96,48,1]}}},
                 "style":{"smooth":{"stroke_width":2.0}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(5, stroke.points.size)
        assertEquals(stroke.points.first().x, stroke.points.last().x, 1e-9)
        assertEquals(stroke.points.first().y, stroke.points.last().y, 1e-9)
        // Centre 96,48 px +/- 24,12 px is 72,36 pt +/- 18,9 pt.
        assertEquals(54.0, stroke.points[0].x, 1e-9)
        assertEquals(27.0, stroke.points[0].y, 1e-9)
        assertEquals(90.0, stroke.points[2].x, 1e-9)
        assertEquals(45.0, stroke.points[2].y, 1e-9)
    }

    @Test
    fun `an ellipse samples its perimeter and closes`() {
        val stroke = shapeStrokeToStroke(
            parseShape(
                """
                {"shape":{"ellipse":{"radii":[96.0,48.0],
                   "transform":{"affine":[1,0,0,0,1,0,96,96,1]}}},
                 "style":{"smooth":{"stroke_width":2.0}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(33, stroke.points.size)
        assertEquals(stroke.points.first().x, stroke.points.last().x, 1e-9)
        // Angle 0 sits at the centre plus the x radius: (96+96, 96) px = (144, 72) pt.
        assertEquals(144.0, stroke.points[0].x, 1e-9)
        assertEquals(72.0, stroke.points[0].y, 1e-9)
        // A quarter turn on is the centre plus the y radius.
        assertEquals(72.0, stroke.points[8].x, 1e-9)
        assertEquals(108.0, stroke.points[8].y, 1e-9)
    }

    @Test
    fun `a cubic bezier is sampled from its start to its end`() {
        val stroke = shapeStrokeToStroke(
            parseShape(
                """
                {"shape":{"cubbez":{"start":[0.0,0.0],"cp1":[0.0,96.0],
                   "cp2":[96.0,96.0],"end":[96.0,0.0]}},
                 "style":{"smooth":{"stroke_width":2.0}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(24, stroke.points.size)
        assertEquals(0.0, stroke.points.first().x, 1e-9)
        assertEquals(0.0, stroke.points.first().y, 1e-9)
        assertEquals(72.0, stroke.points.last().x, 1e-9)
        assertEquals(0.0, stroke.points.last().y, 1e-9)
        // The curve bulges towards the control points without reaching them.
        assertTrue(stroke.points[12].y > 18.0 && stroke.points[12].y < 54.0)
    }

    @Test
    fun `a polygon repeats its first vertex and a polyline does not`() {
        val path = """"start":[0.0,0.0],"path":[[96.0,0.0],[96.0,96.0]]"""
        val open = shapeStrokeToStroke(parseShape("""{"shape":{"polyline":{$path}}}"""))!!
        val shut = shapeStrokeToStroke(parseShape("""{"shape":{"polygon":{$path}}}"""))!!
        assertEquals(3, open.points.size)
        assertEquals(4, shut.points.size)
        assertEquals(0.0, shut.points.last().x, 1e-9)
        assertEquals(0.0, shut.points.last().y, 1e-9)
        // No stroke_width anywhere: the 2.0 px default, uniform along the shape.
        for (point in open.points) assertEquals(1.5, point.width, 1e-9)
    }

    @Test
    fun `an unknown shape tag converts to null`() {
        assertNull(shapeStrokeToStroke(parseShape("""{"shape":{"spiral":{"turns":3}}}""")))
    }

    @Test
    fun `a brushstroke is not a shapestroke`() {
        assertNull(shapeStrokeToStroke(strokes("plain")[0]))
    }

    @Test
    fun `a segment with no end is skipped rather than breaking the stroke`() {
        val stroke = brushStrokeToStroke(
            parseStroke(
                """
                {"path":{"start":{"pos":[0.0,0.0],"pressure":1.0},
                  "segments":[{"future":{"control":[1.0,1.0]}},
                    {"lineto":{"end":{"pos":[8.0,0.0],"pressure":1.0}}}]},
                 "style":{"smooth":{"stroke_width":2.0}}}
                """.trimIndent(),
            ),
        )!!
        assertEquals(2, stroke.points.size)
        assertEquals(6.0, stroke.points[1].x, 1e-9)
    }
}
