package com.xopp.android.format.xml

/**
 * A minimal pull parser for the `.xopp` subset (no namespaces, no DTD, no CDATA). Emits
 * [Event.START], [Event.END], [Event.TEXT] and [Event.EOF]. Self-closing tags emit START then
 * END. Attribute insertion order is preserved. Comments, the XML declaration, and doctype are
 * skipped. Kept dependency-free so the same code runs on-device and in JVM unit tests.
 */
class XmlPullReader(private val s: String) {

    /** Thrown when the input ends mid-tag (interrupted write, partial download). */
    class TruncatedXmlException(message: String) : IllegalArgumentException(message)

    enum class Event { START, END, TEXT, EOF }

    var event: Event = Event.EOF
        private set
    var name: String = ""
        private set
    var text: String = ""
        private set

    private val attrs = LinkedHashMap<String, String>()
    private var pos = 0
    private var pendingEndName: String? = null

    fun attr(name: String): String? = attrs[name]
    fun attributes(): Map<String, String> = attrs

    fun next(): Event {
        pendingEndName?.let {
            pendingEndName = null
            name = it
            event = Event.END
            return event
        }
        if (pos >= s.length) {
            event = Event.EOF
            return event
        }
        return if (s[pos] == '<') readTag() else readText()
    }

    private fun readText(): Event {
        val lt = s.indexOf('<', pos)
        val endText = if (lt < 0) s.length else lt
        val raw = s.substring(pos, endText)
        pos = endText
        if (raw.isBlank()) {
            // Ignore inter-element whitespace; move on to the next real event.
            return next()
        }
        text = decodeEntities(raw)
        event = Event.TEXT
        return event
    }

    private fun readTag(): Event {
        // pos points at '<'.
        when {
            s.startsWith("<?", pos) -> { skipUntil("?>"); return next() }
            s.startsWith("<!--", pos) -> { skipUntil("-->"); return next() }
            s.startsWith("<!", pos) -> { skipUntil(">"); return next() }
            s.startsWith("</", pos) -> return readEndTag()
            else -> return readStartTag()
        }
    }

    private fun readEndTag(): Event {
        val gt = s.indexOf('>', pos)
        if (gt < 0) truncated("unterminated end tag")
        name = s.substring(pos + 2, gt).trim()
        pos = gt + 1
        event = Event.END
        return event
    }

    private fun readStartTag(): Event {
        attrs.clear()
        var i = pos + 1
        val nameStart = i
        while (i < s.length && !isNameEnd(s[i])) i++
        name = s.substring(nameStart, i)
        // Attributes.
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            val c = s[i]
            if (c == '/' || c == '>') break
            val attrNameStart = i
            while (i < s.length && s[i] != '=' && !s[i].isWhitespace()) i++
            val attrName = s.substring(attrNameStart, i)
            while (i < s.length && (s[i].isWhitespace() || s[i] == '=')) i++
            if (i >= s.length) truncated("unterminated attribute '$attrName'")
            val quote = s[i]
            i++
            val valStart = i
            while (i < s.length && s[i] != quote) i++
            if (i >= s.length) truncated("unterminated attribute value for '$attrName'")
            val value = s.substring(valStart, i)
            i++ // consume closing quote
            attrs[attrName] = decodeEntities(value)
        }
        val selfClosing = i < s.length && s[i] == '/'
        val gt = s.indexOf('>', i)
        if (gt < 0) truncated("unterminated start tag '$name'")
        pos = gt + 1
        if (selfClosing) pendingEndName = name
        event = Event.START
        return event
    }

    /** End parsing with a controlled error; leaves the reader at EOF so nothing loops. */
    private fun truncated(what: String): Nothing {
        pos = s.length
        event = Event.EOF
        throw TruncatedXmlException("Truncated XML: $what")
    }

    private fun skipUntil(marker: String) {
        val idx = s.indexOf(marker, pos)
        pos = if (idx < 0) s.length else idx + marker.length
    }

    private fun isNameEnd(c: Char): Boolean = c.isWhitespace() || c == '>' || c == '/'

    private companion object {
        fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i)
                    if (semi > i) {
                        when (val ent = s.substring(i + 1, semi)) {
                            "amp" -> sb.append('&')
                            "lt" -> sb.append('<')
                            "gt" -> sb.append('>')
                            "quot" -> sb.append('"')
                            "apos" -> sb.append('\'')
                            else -> {
                                // Malformed numeric entities (empty, non-numeric, overflowing,
                                // out-of-range, surrogate) fall back to the raw text, as the
                                // unknown-named-entity branch does.
                                val code = if (ent.startsWith("#")) {
                                    if (ent.startsWith("#x") || ent.startsWith("#X"))
                                        ent.substring(2).toIntOrNull(16)
                                    else ent.substring(1).toIntOrNull()
                                } else {
                                    null
                                }
                                if (code != null && code in 0..0x10FFFF && code !in 0xD800..0xDFFF) {
                                    sb.appendCodePoint(code)
                                } else {
                                    sb.append(s, i, semi + 1)
                                }
                            }
                        }
                        i = semi + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
