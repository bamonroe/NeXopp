package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format **drift test** over a set of desktop-format `.xopp` fixtures (see
 * `app/src/test/resources/fixtures/`), each validated to load in desktop Xournal++ 1.3.5 before
 * being committed. Two jobs:
 *
 *  1. **Round-trip fidelity** — every fixture must reserialize to an equal model
 *     (`open → toXml → parseXml` is a no-op on the model), so a parser/writer change that would
 *     silently corrupt a real file fails the build.
 *  2. **Schema coverage** — the fixture set collectively exercises the schema surface documented
 *     in `docs/architecture.md` (all background styles, multi-page, multiple layers, every
 *     element type, pressure vs. uniform stroke width, highlighter alpha). If a documented
 *     feature loses coverage, the aggregate assertions here catch it.
 */
class FormatDriftTest {

    private val fixtures = listOf("plain", "backgrounds", "layers", "text-image")

    private fun load(name: String): Document {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.xopp")
            ?: error("missing fixture resource fixtures/$name.xopp")
        return stream.use { Xopp.open(it) }
    }

    private fun allElements(doc: Document) =
        doc.pages.flatMap { p -> p.layers.flatMap { it.elements } }

    @Test fun everyFixtureReserializesToEqualModel() {
        for (name in fixtures) {
            val doc1 = load(name)
            assertTrue("$name: expected at least one page", doc1.pages.isNotEmpty())
            val doc2 = Xopp.parseXml(Xopp.toXml(doc1))
            assertEquals("$name drifted on reserialize", doc1, doc2)
        }
    }

    @Test fun backgroundsFixtureCoversEveryStyle() {
        val styles = load("backgrounds").pages
            .map { (it.background as Background.Solid).style }
        assertEquals(listOf("plain", "lined", "ruled", "graph", "dotted"), styles)
    }

    @Test fun fixtureSetCoversMultiPageAndLayers() {
        assertTrue("backgrounds fixture should be multi-page", load("backgrounds").pages.size >= 2)
        val layerCounts = load("layers").pages.map { it.layers.size }
        assertEquals(listOf(3), layerCounts) // z-ordered layers preserved
    }

    @Test fun fixtureSetCoversEveryElementType() {
        val all = fixtures.flatMap { allElements(load(it)) }
        assertTrue("no strokes", all.any { it is Stroke })
        assertTrue("no text", all.any { it is TextElement })
        assertTrue("no image", all.any { it is ImageElement })
        assertTrue("no teximage", all.any { it is TexImageElement })
    }

    @Test fun strokesCoverPressureUniformWidthAndHighlighterAlpha() {
        val strokes = fixtures.flatMap { allElements(load(it)) }.filterIsInstance<Stroke>()
        assertTrue("no pressure (per-vertex width) stroke", strokes.any { !it.uniformWidth })
        assertTrue("no uniform-width stroke", strokes.any { it.uniformWidth })
        val hl = strokes.filter { it.tool == Tool.HIGHLIGHTER }
        assertTrue("no highlighter stroke", hl.isNotEmpty())
        assertTrue("highlighter should carry sub-opaque alpha", hl.all { (it.color ushr 24) < 0xFF })
    }

    @Test fun textEntitiesDecodeAndImageDecodes() {
        val els = allElements(load("text-image"))
        val text = els.filterIsInstance<TextElement>().single()
        assertEquals("a & b < c > d", text.content) // XML entities decoded on read
        val img = els.filterIsInstance<ImageElement>().single()
        assertTrue("image bytes should decode from base64", img.data.isNotEmpty())
    }
}
