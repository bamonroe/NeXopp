package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test

class XoppRoundTripTest {

    // A compact document exercising every element type, pressure widths, alpha, preserved
    // stroke attributes (ts/fn), and XML-escaped text.
    private val sample = """
        <?xml version="1.0" standalone="no"?>
        <xournal creator="xournalpp 1.1.1+dev" fileversion="4">
        <title>Xournal++ document - see </title>
        <preview>QUJD</preview>
        <page width="595.27559100" height="841.88976400">
        <background type="solid" color="#ffffffff" style="graph"/>
        <layer>
        <stroke tool="pen" ts="0" fn="" color="#000000ff" width="0.85 0.56 0.63" capStyle="round">10.0 20.0 11.0 21.0 12.0 22.0</stroke>
        <stroke tool="highlighter" color="#ffff0080" width="2.5">1.0 2.0 3.0 4.0</stroke>
        <text font="Sans" size="12.00000000" x="5.0" y="6.0" color="#101010ff">a &amp; b &lt; c</text>
        <image left="0.0" top="0.0" right="10.0" bottom="10.0">QUJD</image>
        <teximage text="x^2" color="#000000ff" left="1.0" top="2.0" right="3.0" bottom="4.0">x^2</teximage>
        </layer>
        </page>
        </xournal>
    """.trimIndent()

    @Test fun parsesEveryElement() {
        val doc = Xopp.parseXml(sample)
        assertEquals("xournalpp 1.1.1+dev", doc.creator)
        assertEquals("4", doc.fileVersion)
        assertEquals("QUJD", doc.preview)
        assertEquals(1, doc.pages.size)

        val page = doc.pages[0]
        assertEquals(595.27559100, page.width, 1e-9)
        val bg = page.background as Background.Solid
        assertEquals("graph", bg.style)
        assertEquals(0xFFFFFFFF.toInt(), bg.color)

        val els = page.layers.single().elements
        assertEquals(5, els.size)

        val pen = els[0] as Stroke
        assertEquals(Tool.PEN, pen.tool)
        assertEquals(3, pen.points.size)
        assertEquals(false, pen.uniformWidth)
        assertEquals(0.56, pen.points[1].width, 1e-9)
        // ts and fn are not modelled but must be preserved.
        assertEquals("0", pen.extraAttrs["ts"])
        assertTrue(pen.extraAttrs.containsKey("fn"))

        val hl = els[1] as Stroke
        assertEquals(Tool.HIGHLIGHTER, hl.tool)
        assertTrue(hl.uniformWidth)
        assertEquals(0x80.toInt(), hl.color ushr 24) // alpha preserved

        val text = els[2] as TextElement
        assertEquals("a & b < c", text.content) // entities decoded

        val img = els[3] as ImageElement
        assertEquals(3, img.data.size) // "QUJD" -> "ABC"

        val tex = els[4] as TexImageElement
        assertEquals("x^2", tex.latex)
    }

    @Test fun modelSurvivesReserialize() {
        val doc1 = Xopp.parseXml(sample)
        val doc2 = Xopp.parseXml(Xopp.toXml(doc1))
        assertEquals(doc1, doc2)
    }

    @Test fun survivesGzipRoundTrip() {
        val doc1 = Xopp.parseXml(sample)
        val bytes = ByteArrayOutputStream().also { Xopp.save(doc1, it) }.toByteArray()
        val doc2 = Xopp.open(ByteArrayInputStream(bytes))
        assertEquals(doc1, doc2)
    }
}
