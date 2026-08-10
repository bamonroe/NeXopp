package com.nexopp.format.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Malformed and truncated input must degrade cleanly: no crash, no hang. */
class XmlPullReaderMalformedTest {

    private fun firstAttr(xml: String): String {
        val r = XmlPullReader(xml)
        r.next()
        return r.attr("v") ?: error("no attribute")
    }

    @Test fun decodesWellFormedNumericEntities() {
        assertEquals("A", firstAttr("""<e v="&#65;"/>"""))
        assertEquals("A", firstAttr("""<e v="&#x41;"/>"""))
    }

    @Test fun emptyNumericEntityKeptRaw() {
        assertEquals("&#;", firstAttr("""<e v="&#;"/>"""))
        assertEquals("&#x;", firstAttr("""<e v="&#x;"/>"""))
    }

    @Test fun nonNumericEntityKeptRaw() {
        assertEquals("&#zz;", firstAttr("""<e v="&#zz;"/>"""))
        assertEquals("&#xzz;", firstAttr("""<e v="&#xzz;"/>"""))
    }

    @Test fun overflowingEntityKeptRaw() {
        assertEquals("&#99999999999;", firstAttr("""<e v="&#99999999999;"/>"""))
        assertEquals("&#xFFFFFFFFFF;", firstAttr("""<e v="&#xFFFFFFFFFF;"/>"""))
    }

    @Test fun outOfRangeAndSurrogateEntitiesKeptRaw() {
        assertEquals("&#1114112;", firstAttr("""<e v="&#1114112;"/>"""))
        assertEquals("&#xD800;", firstAttr("""<e v="&#xD800;"/>"""))
        assertEquals("&#-5;", firstAttr("""<e v="&#-5;"/>"""))
    }

    @Test fun unknownNamedEntityKeptRaw() {
        assertEquals("&nope;", firstAttr("""<e v="&nope;"/>"""))
    }

    @Test(timeout = 5_000)
    fun truncatedInputFailsFastWithoutHanging() {
        val truncated = listOf(
            "<xournal version=",
            """<xournal version="0.4""",
            "<xournal",
            "<page width=\"100\"",
            "<xournal><page></pag",
        )
        for (xml in truncated) {
            val r = XmlPullReader(xml)
            assertThrows(XmlPullReader.TruncatedXmlException::class.java) {
                while (r.next() != XmlPullReader.Event.EOF) { /* drain */ }
            }
        }
    }
}
