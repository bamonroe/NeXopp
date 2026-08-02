package com.xopp.android.format

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * **XML-equality** round-trip test: load each desktop-written fixture, re-emit it with
 * [XoppWriter], and assert the emitted XML is equal to the *source* XML once both are
 * normalized. The sibling [FormatDriftTest] compares parsed *models*; this one compares the
 * bytes we put on disk, so a writer change that silently reshapes the file — a dropped
 * attribute, a renamed element, a reordered child — fails the build even when the model
 * happens to survive.
 *
 * ### What "normalized" means (and why)
 * Two writers may spell the same document differently without any loss of fidelity, so the
 * comparison canonicalizes exactly these incidental differences and nothing else:
 *
 *  - **Formatting** — the XML prolog and all inter-element whitespace are dropped.
 *  - **Attribute order** — attributes are sorted by name (desktop writes `tool color width ts fn`,
 *    we write `tool ts fn color width`; on-disk order carries no meaning).
 *  - **Number precision** — every number, in an attribute or in text, is rounded to 2 decimals
 *    and stripped of trailing zeros (desktop emits `100.00`, we emit `100.00000000`).
 *  - **The `<title>` boilerplate** — both writers emit a fixed advertising string that differs
 *    only by which Xournal++ URL was current; its text is normalized away.
 *
 * Anything else — an element, an attribute, a coordinate, a colour — must match exactly.
 */
class XmlEqualityRoundTripTest {

    private val fixtures = listOf("plain", "backgrounds", "layers", "text-image")

    @Test fun everyFixtureReemitsToEqualXml() {
        for (name in fixtures) {
            val source = sourceXml(name)
            val emitted = Xopp.toXml(Xopp.parseXml(source))
            assertEquals("$name: emitted XML drifted from source", canonical(source), canonical(emitted))
        }
    }

    /** Our own writer must be a fixed point: re-reading what we wrote re-emits it byte-for-byte. */
    @Test fun writerIsIdempotent() {
        for (name in fixtures) {
            val once = Xopp.toXml(Xopp.parseXml(sourceXml(name)))
            val twice = Xopp.toXml(Xopp.parseXml(once))
            assertEquals("$name: writer is not a fixed point", once, twice)
        }
    }

    /** The fixture's original desktop-written XML, ungzipped. */
    private fun sourceXml(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name.xopp")
            ?.use { GZIPInputStream(it).readBytes().decodeToString() }
            ?: error("missing fixture fixtures/$name.xopp")

    // ---- normalization -------------------------------------------------------------------

    /** Renders [xml] as a canonical single-line string; see the class doc for the rules. */
    private fun canonical(xml: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        return StringBuilder().also { render(doc.documentElement, it) }.toString()
    }

    private fun render(el: Element, out: StringBuilder) {
        out.append('<').append(el.tagName)
        val attrs = el.attributes
        (0 until attrs.length)
            .map { attrs.item(it) }
            .sortedBy { it.nodeName }
            .forEach { out.append(' ').append(it.nodeName).append("=\"").append(numbers(it.nodeValue)).append('"') }
        out.append('>')
        // `<title>` holds boilerplate that differs harmlessly between writers.
        val text = if (el.tagName == "title") "" else textOf(el)
        out.append(text)
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val kid = kids.item(i)
            if (kid is Element) render(kid, out)
        }
        out.append("</").append(el.tagName).append('>')
    }

    private fun textOf(el: Element): String {
        val kids = el.childNodes
        val raw = buildString {
            for (i in 0 until kids.length) {
                val kid = kids.item(i)
                if (kid.nodeType == Node.TEXT_NODE || kid.nodeType == Node.CDATA_SECTION_NODE) {
                    append(kid.nodeValue)
                }
            }
        }
        return numbers(raw.trim())
    }

    /** Rounds every number in [s] to 2 decimals with trailing zeros stripped. */
    private fun numbers(s: String): String =
        NUMBER.replace(s) { m ->
            val v = m.value.toDouble()
            val rounded = Math.round(v * 100.0) / 100.0
            if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        }

    private companion object {
        val NUMBER = Regex("""-?\d+\.\d+""")
    }
}
