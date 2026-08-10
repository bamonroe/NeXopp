package com.nexopp.format

import com.nexopp.format.model.Background
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core-rule fidelity guard: markup this build doesn't model — an unknown attribute on any
 * element, a whole unknown element inside a layer, a custom `<title>` — must survive a load/save
 * round trip untouched, so a file authored by a newer or vendor desktop build isn't quietly
 * stripped when it is edited here. See `docs/architecture.md` for the schema mapping.
 */
class UnknownMarkupRoundTripTest {

    /** Wrap [layerBody] in the smallest valid document, with extras sprinkled on every container. */
    private fun docXml(layerBody: String): String = """
        <?xml version="1.0" standalone="no"?>
        <xournal creator="Xournal++ 1.2.3" fileversion="4">
        <title>My own title</title>
        <page width="595.00000000" height="842.00000000" future="page-x">
        <background type="solid" color="#ffffffff" style="plain" rulingSpacing="17.5"/>
        <layer name="Layer 1" future="layer-x">
        $layerBody
        </layer>
        </page>
        </xournal>
    """.trimIndent()

    private fun reserialize(xml: String) = Xopp.toXml(Xopp.parseXml(xml))

    @Test fun unknownAttributesSurviveOnEveryElementKind() {
        val xml = docXml(
            """
            <stroke tool="pen" color="#000000ff" width="1.00000000" fn="a.mp3" ts="00:01">0 0 1 1</stroke>
            <text font="Sans" size="12.00000000" x="1.00000000" y="2.00000000" color="#000000ff" fn="a.mp3" ts="00:02">hi</text>
            <image left="0.00000000" top="0.00000000" right="1.00000000" bottom="1.00000000" future="img-x"></image>
            <teximage text="x^2" color="#000000ff" left="0.00000000" top="0.00000000" right="1.00000000" bottom="1.00000000" future="tex-x"></teximage>
            """.trimIndent(),
        )
        val doc = Xopp.parseXml(xml)
        val page = doc.pages.single()
        val layer = page.layers.single()
        assertEquals(mapOf("future" to "page-x"), page.extraAttrs)
        assertEquals(mapOf("rulingSpacing" to "17.5"), page.background.extraAttrs)
        assertEquals(mapOf("future" to "layer-x"), layer.extraAttrs)
        // Audio links on a text box are the consequential case: desktop puts the same fn/ts pair
        // on text that it puts on strokes.
        val text = layer.elements.filterIsInstance<TextElement>().single()
        assertEquals(mapOf("fn" to "a.mp3", "ts" to "00:02"), text.extraAttrs)
        assertEquals(mapOf("future" to "img-x"), layer.elements.filterIsInstance<ImageElement>().single().extraAttrs)
        assertEquals(mapOf("future" to "tex-x"), layer.elements.filterIsInstance<TexImageElement>().single().extraAttrs)
        // …and they are all still there after a save + reload.
        assertEquals(doc, Xopp.parseXml(reserialize(xml)))
    }

    @Test fun unknownElementInsideALayerIsKeptVerbatim() {
        val xml = docXml("""<future kind="widget" n="2">inner <b>markup</b></future>""")
        val raw = Xopp.parseXml(xml).pages.single().layers.single().elements.single() as RawElement
        assertEquals("future", raw.name)
        assertEquals(mapOf("kind" to "widget", "n" to "2"), raw.attrs)
        assertEquals("inner <b>markup</b>", raw.body)
        val out = reserialize(xml)
        assertTrue(out, """<future kind="widget" n="2">inner <b>markup</b></future>""" in out)
    }

    @Test fun selfClosingUnknownElementStaysSelfClosing() {
        val out = reserialize(docXml("""<future kind="widget"/>"""))
        assertTrue(out, """<future kind="widget"/>""" in out)
    }

    @Test fun customTitleSurvivesButTheDefaultBannerIsNotStored() {
        assertEquals("My own title", Xopp.parseXml(docXml("")).title)
        assertTrue(reserialize(docXml("") ).contains("<title>My own title</title>"))
        // Our own writer's banner reads back as "no title of its own", so a plain document is
        // unchanged by a round trip.
        val plain = Xopp.parseXml(reserialize(docXml("").replace("My own title", XoppWriter.DEFAULT_TITLE)))
        assertEquals(null, plain.title)
    }

    @Test fun teximageKeepsTheSourceWhereTheFileHadIt() {
        val inBody = docXml(
            """<teximage color="#000000ff" left="0.00000000" top="0.00000000" right="1.00000000" bottom="1.00000000">x^2</teximage>""",
        )
        val tex = Xopp.parseXml(inBody).pages.single().layers.single().elements.single() as TexImageElement
        assertEquals("x^2", tex.latex)
        assertFalse("the source was in the body, not an attribute", tex.latexInAttribute)
        val out = reserialize(inBody)
        assertFalse("must not gain a text attribute it never had", "text=\"x^2\"" in out)
        assertTrue(out, ">x^2</teximage>" in out)
        // The attribute form is equally preserved.
        val inAttr = docXml(
            """<teximage text="x^2" color="#000000ff" left="0.00000000" top="0.00000000" right="1.00000000" bottom="1.00000000"></teximage>""",
        )
        assertTrue("""text="x^2"""" in reserialize(inAttr))
        assertEquals(Xopp.parseXml(inAttr), Xopp.parseXml(reserialize(inAttr)))
    }

    @Test fun solidBackgroundExtrasSurviveAStyleChange() {
        val bg = Xopp.parseXml(docXml("")).pages.single().background as Background.Solid
        assertEquals(mapOf("rulingSpacing" to "17.5"), bg.copy(style = "graph").extraAttrs)
    }
}
