package com.nexopp.format

import com.nexopp.format.model.ImageElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Desktop Xournal++ line-wraps the base64 image body; that must load and round-trip. */
class WrappedImageBase64Test {

    private fun docWithImageBody(body: String) = """
        <xournal creator="xournalpp" fileversion="4">
        <page width="100.0" height="100.0">
        <background type="solid" color="#ffffffff" style="plain"/>
        <layer>
        <image left="0.0" top="0.0" right="10.0" bottom="10.0">$body</image>
        </layer>
        </page>
        </xournal>
    """.trimIndent()

    private fun firstImage(xml: String): ImageElement =
        Xopp.parseXml(xml).pages[0].layers[0].elements.filterIsInstance<ImageElement>().first()

    @Test fun lineWrappedBase64Decodes() {
        // "ABCDEFGHI" base64-encoded, wrapped across lines like the desktop writes it.
        val expected = "ABCDEFGHI".toByteArray()
        val wrapped = "QUJDR\nEVGR0hJ\n"
        assertArrayEquals(expected, firstImage(docWithImageBody(wrapped)).data)
    }

    @Test fun lineWrappedImageRoundTrips() {
        val doc = Xopp.parseXml(docWithImageBody("QUJDR\nEVGR0hJ"))
        val reparsed = Xopp.parseXml(Xopp.toXml(doc))
        assertArrayEquals("ABCDEFGHI".toByteArray(), firstImage(Xopp.toXml(doc)).data)
        assertEquals(1, reparsed.pages.size)
    }

    @Test fun undecodableBase64YieldsEmptyImageInsteadOfFailing() {
        assertEquals(0, firstImage(docWithImageBody("!!!not base64!!!")).data.size)
    }
}
