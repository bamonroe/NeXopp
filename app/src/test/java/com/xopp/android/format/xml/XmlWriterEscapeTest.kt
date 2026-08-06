package com.xopp.android.format.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribute-value escaping. A literal newline, carriage return or tab inside an attribute is
 * normalised to a space by any conforming XML parser, so writing it raw would break round-trip
 * with desktop Xournal++ for exactly the preserved-unknown-attribute values we now carry.
 */
class XmlWriterEscapeTest {

    private fun write(value: String): String = buildString {
        XmlWriter(this).start("e").attr("v", value).end()
    }

    @Test fun controlCharactersAreEscapedInAttributeValues() {
        assertEquals("""<e v="a&#10;b&#13;c&#9;d"/>""", write("a\nb\rc\td"))
    }

    @Test fun escapedControlCharactersReadBackUnchanged() {
        val value = "line one\nline two\tindented"
        val r = XmlPullReader(write(value))
        r.next()
        assertEquals(value, r.attr("v"))
    }

    @Test fun elementTextKeepsItsLineBreaksLiterally() {
        // Text content is not attribute-normalised, so escaping there would only add noise.
        val out = buildString { XmlWriter(this).start("e").text("one\ntwo").end() }
        assertTrue(out, "one\ntwo" in out)
    }

    @Test fun theUsualMarkupCharactersAreStillEscaped() {
        assertEquals("""<e v="&amp;&lt;&gt;&quot;"/>""", write("&<>\""))
    }
}
