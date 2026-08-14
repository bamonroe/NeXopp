package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import com.nexopp.format.model.Background
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole `.rnote` → [com.nexopp.format.model.Document] assembly, against the fixtures and their
 * `.xopp` twins in `app/src/test/resources/fixtures/`. Every expectation here is the import half of
 * `docs/architecture.md`, "Decision (2026-08-12): canvas ↔ pages, layers & backgrounds".
 */
class RnoteDocumentReaderTest {

    /** A4 in pt, what `format.width/height` px ÷ 4/3 comes to. */
    private val a4Width = 595.28
    private val a4Height = 841.89

    private fun import(name: String): RnoteImport {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")
        return readDocument(RnoteSnapshot.parse(wrapper.snapshot))
    }

    @Test
    fun `the five-page canvas is cut back into five A4 pages`() {
        val document = import("backgrounds").document
        assertEquals(5, document.pages.size)
        for (page in document.pages) {
            assertEquals(a4Width, page.width, 0.01)
            assertEquals(a4Height, page.height, 0.01)
        }
    }

    @Test
    fun `each of those pages holds its one stroke, page-local`() {
        val document = import("backgrounds").document
        for (page in document.pages) {
            val strokes = page.layers.flatMap { it.elements }.filterIsInstance<Stroke>()
            assertEquals(1, strokes.size)
            val first = strokes.single().points.first()
            assertEquals(30.0, first.x, 1.0)
            assertEquals(30.0, first.y, 1.0)
        }
    }

    @Test
    fun `the canvas background is copied onto every page`() {
        for (page in import("backgrounds").document.pages) {
            assertTrue(page.background is Background.Solid)
        }
    }

    @Test
    fun `the layer slots become the same stack on every page, highlighter below the pen`() {
        val document = import("layers").document
        assertEquals(1, document.pages.size)
        val page = document.pages.single()
        assertEquals(listOf("highlighter", "user_layer 0"), page.layers.map { it.name })
        assertEquals(1, page.layers[0].elements.size)
        assertEquals(2, page.layers[1].elements.size)
    }

    @Test
    fun `an image lands on the image layer and text on the pen layer`() {
        val page = import("text-image").document.pages.single()
        val byName = page.layers.associateBy { it.name }
        assertTrue(byName.getValue("image").elements.single() is ImageElement)
        assertTrue(byName.getValue("user_layer 0").elements.single() is TextElement)
    }

    @Test
    fun `an empty canvas still gives one drawable page with its background`() {
        val document = import("empty").document
        val page = document.pages.single()
        assertEquals("dotted", (page.background as Background.Solid).style)
        assertEquals(emptyList<Any>(), page.layers.flatMap { it.elements })
        assertEquals(1, page.layers.size)
    }

    @Test
    fun `a document with nothing unconvertible reports nothing`() {
        assertEquals(emptyList<String>(), import("plain").skipped)
    }

    @Test
    fun `the creator is NeXopp`() {
        assertEquals("NeXopp", import("plain").document.creator)
    }

    @Test
    fun `an unconvertible stroke kind is counted into the report, not thrown`() {
        val snapshot = import("plain")
        val strokes = snapshot.document.pages.single().layers.flatMap { it.elements }
        assertTrue(strokes.isNotEmpty())

        val source = RnoteSnapshot.parse(
            javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/plain.rnote")!!
                .use { RnoteContainer.open(it) }.snapshot,
        )
        val withSvg = source.copy(
            strokes = source.strokes + source.strokes.first().copy(kind = "vectorimage", z = 999L),
        )
        assertEquals(
            listOf("1 vectorimage stroke could not be converted"),
            readDocument(withSvg).skipped,
        )
    }

    @Test
    fun `a text box that lost its ranged styling is reported alongside the skips`() {
        // Ranged bold over half the string: the text crosses, the styling cannot, and `.xopp` has
        // no way to split the box — so it is reported rather than silently flattened.
        val body = JsonReader(
            """
            {"text":"hello","transform":{"affine":[1,0,0,-0,1,0,0,0,1]},
             "text_style":{"font_family":"Sans","font_size":16.0,
              "ranged_text_attributes":[{"range":{"start":0,"end":2},
               "attribute":{"font_weight":700}}]}}
            """,
        ).parse()
        val source = RnoteSnapshot.parse(
            javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/plain.rnote")!!
                .use { RnoteContainer.open(it) }.snapshot,
        )
        val withText = source.copy(
            strokes = source.strokes +
                RnoteStroke(99, "textstroke", body, 999L, "user_layer", 0),
        )
        val imported = readDocument(withText)
        assertEquals(listOf("1 text box lost some of its styling"), imported.skipped)
        // The words themselves are never dropped.
        assertTrue(
            imported.document.pages.flatMap { p -> p.layers.flatMap { it.elements } }
                .filterIsInstance<TextElement>().any { it.content == "hello" },
        )
    }
}
