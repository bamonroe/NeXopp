package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The snapshot parser against the five ground-truth fixtures written by `rnote-cli` 0.14.2
 * (`app/src/test/resources/fixtures/rnote/`), plus a hand-built pre-0.13 payload for the flat
 * `document.format` shape no fixture can cover.
 */
class RnoteSnapshotTest {

    private fun parse(name: String): RnoteSnapshot {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")
        return RnoteSnapshot.parse(wrapper.snapshot)
    }

    @Test
    fun `every fixture parses into a snapshot`() {
        for (name in listOf("empty", "plain", "backgrounds", "layers", "text-image")) {
            val snapshot = parse(name)
            assertEquals(name, "infinite", snapshot.layout)
            assertEquals(name, 96.0, snapshot.format.dpi, 0.0)
        }
    }

    @Test
    fun `plain has three brush strokes in z order on an A4 canvas`() {
        val snapshot = parse("plain")
        assertEquals(793.701, snapshot.format.width, 1e-6)
        assertEquals(1122.52, snapshot.format.height, 1e-6)
        assertEquals(96.0, snapshot.format.dpi, 0.0)
        assertEquals("portrait", snapshot.format.orientation)
        assertEquals(listOf("brushstroke", "brushstroke", "brushstroke"), snapshot.strokes.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L), snapshot.strokes.map { it.z })
        assertEquals(listOf(1, 2, 3), snapshot.strokes.map { it.index })
        // The last stroke was drawn with the highlighter, which lives on its own layer.
        assertEquals(listOf("user_layer", "user_layer", "highlighter"), snapshot.strokes.map { it.layer })
        assertEquals(listOf(0, 0, null), snapshot.strokes.map { it.userLayer })
    }

    @Test
    fun `text-image keeps the image on the image layer and the text on a user layer`() {
        val strokes = parse("text-image").strokes
        assertEquals(2, strokes.size)
        assertEquals("bitmapimage", strokes[0].kind)
        assertEquals("image", strokes[0].layer)
        assertNull(strokes[0].userLayer)
        assertEquals("textstroke", strokes[1].kind)
        assertEquals("user_layer", strokes[1].layer)
        assertEquals(0, strokes[1].userLayer)
        // The body is handed on uninterpreted, but it is the tag's value, not the whole stroke.
        assertEquals("a & b < c > d", strokes[1].body.obj("text")?.str())
    }

    @Test
    fun `empty has no strokes and a dotted background`() {
        val snapshot = parse("empty")
        assertEquals(emptyList<String>(), snapshot.strokes.map { it.kind })
        assertEquals("dots", snapshot.background.pattern)
        assertEquals(32.0, snapshot.background.patternWidth, 0.0)
        assertEquals(32.0, snapshot.background.patternHeight, 0.0)
        assertEquals(RnoteColor(1.0, 1.0, 1.0, 1.0), snapshot.background.color)
        assertEquals(0.0, snapshot.docX, 0.0)
        assertEquals(1123.0, snapshot.docWidth, 0.0)
    }

    @Test
    fun `backgrounds carries every stroke slot but the empty one`() {
        // Six slots, of which slot 0 is the always-null slotmap head — five real strokes.
        val strokes = parse("backgrounds").strokes
        assertEquals(5, strokes.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), strokes.map { it.z })
    }

    @Test
    fun `the pre-0_13 flat document shape parses`() {
        val json = JsonReader(
            """
            {"document":{
              "format":{"width":100.0,"height":200.0,"dpi":72.0,"orientation":"landscape"},
              "background":{"color":{"r":1.0,"g":1.0,"b":1.0,"a":1.0},"pattern":"lines",
                "pattern_size":[16.0,24.0],
                "pattern_color":{"r":0.0,"g":0.0,"b":0.0,"a":1.0}},
              "layout":"fixed_size","x":1.0,"y":2.0,"width":100.0,"height":200.0,
              "snap_positions":false},
             "stroke_components":[{"value":null,"version":0},
               {"value":{"shapestroke":{"shape":"line"}},"version":1}],
             "chrono_components":[{"value":null,"version":0},
               {"value":{"t":7,"layer":{"user_layer":2}},"version":1}]}
            """.trimIndent(),
        ).parse()
        val snapshot = RnoteSnapshot.parse(json)
        assertEquals(100.0, snapshot.format.width, 0.0)
        assertEquals("landscape", snapshot.format.orientation)
        assertEquals("fixed_size", snapshot.layout)
        assertEquals("lines", snapshot.background.pattern)
        assertEquals(16.0, snapshot.background.patternWidth, 0.0)
        assertEquals(24.0, snapshot.background.patternHeight, 0.0)
        assertEquals(1.0, snapshot.docX, 0.0)
        assertEquals(1, snapshot.strokes.size)
        assertEquals("shapestroke", snapshot.strokes[0].kind)
        assertEquals(7L, snapshot.strokes[0].z)
        assertEquals(2, snapshot.strokes[0].userLayer)
    }

    @Test
    fun `an unknown stroke tag is kept rather than rejected`() {
        val json = JsonReader(
            """
            {"document":{"config":{
              "format":{"width":1.0,"height":2.0,"dpi":96.0,"orientation":"portrait"},
              "background":{"color":{"r":0.0,"g":0.0,"b":0.0,"a":1.0},"pattern":"none",
                "pattern_size":[1.0,1.0],
                "pattern_color":{"r":0.0,"g":0.0,"b":0.0,"a":1.0}},
              "layout":"infinite"},"x":0.0,"y":0.0,"width":1.0,"height":2.0},
             "stroke_components":[{"value":{"futurestroke":{"a":1}},"version":1}],
             "chrono_components":[{"value":{"t":1,"layer":"document"}}]}
            """.trimIndent(),
        ).parse()
        val strokes = RnoteSnapshot.parse(json).strokes
        assertEquals("futurestroke", strokes[0].kind)
        assertEquals("document", strokes[0].layer)
        assertNull(strokes[0].userLayer)
    }

    @Test
    fun `a missing key names the key it missed`() {
        val json = JsonReader("""{"document":{"x":0.0}}""").parse()
        val e = try {
            RnoteSnapshot.parse(json)
            error("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertEquals("missing \"document.format\" in the .rnote snapshot", e.message)
    }
}
