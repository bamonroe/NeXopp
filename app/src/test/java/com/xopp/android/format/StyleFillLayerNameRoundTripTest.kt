package com.xopp.android.format

import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `<stroke>` `style`/`fill` attributes and the `<layer name>` attribute must round-trip. */
class StyleFillLayerNameRoundTripTest {

    private val sample = """
        <?xml version="1.0" standalone="no"?>
        <xournal creator="xournalpp" fileversion="4">
        <page width="100" height="100">
        <background type="solid" color="#ffffffff" style="plain"/>
        <layer name="Ink">
        <stroke tool="pen" color="#000000ff" width="1.5" fill="128" style="dashdot">0 0 10 0 10 10</stroke>
        <stroke tool="pen" color="#000000ff" width="1.5">0 0 5 5</stroke>
        </layer>
        </xournal>
    """.trimIndent()

    @Test fun styleFillAndNameParse() {
        val doc = Xopp.parseXml(sample)
        val layer = doc.pages[0].layers.single()
        assertEquals("Ink", layer.name)
        val styled = layer.elements[0] as Stroke
        assertEquals(LineStyle.DASH_DOT, styled.lineStyle)
        assertEquals(128, styled.fill)
        val plain = layer.elements[1] as Stroke
        assertEquals(LineStyle.PLAIN, plain.lineStyle)
        assertNull(plain.fill)
    }

    @Test fun styleFillAndNameSurviveReserialize() {
        val doc = Xopp.parseXml(Xopp.toXml(Xopp.parseXml(sample)))
        val layer = doc.pages[0].layers.single()
        assertEquals("Ink", layer.name)
        val styled = layer.elements[0] as Stroke
        assertEquals(LineStyle.DASH_DOT, styled.lineStyle)
        assertEquals(128, styled.fill)
    }

    @Test fun plainStrokeOmitsStyleAndFillOnWrite() {
        val xml = Xopp.toXml(Xopp.parseXml(sample))
        // Exactly one of the two strokes (the styled one) may carry style/fill; the plain one must not.
        val strokeLines = xml.lineSequence().filter { it.contains("<stroke") }.toList()
        assertEquals(2, strokeLines.size)
        assertEquals(1, strokeLines.count { it.contains("fill=") })
        assertEquals(1, strokeLines.count { it.contains("style=") })
    }
}
