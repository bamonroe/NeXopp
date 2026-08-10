package com.nexopp.format.xml

/**
 * A tiny streaming XML writer for the `.xopp` subset — no namespaces, no third-party library
 * (per the pinned stack in `docs/architecture.md`). Attributes are written in call order, so
 * the caller controls on-disk attribute order for round-trip fidelity.
 */
class XmlWriter(private val out: Appendable) {

    private val open = ArrayDeque<String>()
    private var tagOpen = false

    fun prolog(): XmlWriter {
        out.append("<?xml version=\"1.0\" standalone=\"no\"?>\n")
        return this
    }

    fun start(name: String): XmlWriter {
        closeTag()
        out.append('<').append(name)
        open.addLast(name)
        tagOpen = true
        return this
    }

    fun attr(name: String, value: String): XmlWriter {
        out.append(' ').append(name).append("=\"")
        escapeInto(out, value, attribute = true)
        out.append('"')
        return this
    }

    /** Write element text content (escaped). Must follow [start]. */
    fun text(value: String): XmlWriter {
        closeTag()
        escapeInto(out, value, attribute = false)
        return this
    }

    /** Write raw, already-safe text (e.g. base64) verbatim. */
    fun rawText(value: String): XmlWriter {
        closeTag()
        out.append(value)
        return this
    }

    fun end(): XmlWriter {
        val name = open.removeLast()
        if (tagOpen) {
            out.append("/>")
            tagOpen = false
        } else {
            out.append("</").append(name).append('>')
        }
        return this
    }

    fun newline(): XmlWriter {
        closeTag()
        out.append('\n')
        return this
    }

    private fun closeTag() {
        if (tagOpen) {
            out.append('>')
            tagOpen = false
        }
    }

    private companion object {
        fun escapeInto(out: Appendable, s: String, attribute: Boolean) {
            for (c in s) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> if (attribute) out.append("&quot;") else out.append(c)
                    // A conforming parser normalises literal newline/CR/tab in an attribute value
                    // to a space, so escaping them is what makes such a value round-trip at all.
                    '\n' -> if (attribute) out.append("&#10;") else out.append(c)
                    '\r' -> if (attribute) out.append("&#13;") else out.append(c)
                    '\t' -> if (attribute) out.append("&#9;") else out.append(c)
                    else -> out.append(c)
                }
            }
        }
    }
}
