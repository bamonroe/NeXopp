package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `brushstroke` → `Stroke` conversion against `plain.rnote`, the ground-truth twin of
 * `plain.xopp` (see `docs/architecture.md`, "The stroke & pen mapping"), plus hand-built payloads
 * for the shapes no fixture covers.
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
