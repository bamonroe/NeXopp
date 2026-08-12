package com.nexopp.format.json

/**
 * A minimal RFC 8259 parser producing a [JsonValue] tree. No streaming, no schema, no
 * dependency — the `.rnote` payload is read the same way on-device and in JVM unit tests, and
 * we keep control of the error messages for truncated files (mirroring
 * [com.nexopp.format.xml.XmlPullReader]).
 *
 * Nesting is capped at [MAX_DEPTH] so a hostile or corrupt file throws instead of overflowing
 * the stack.
 */
class JsonReader(private val s: String) {

    /** Thrown on any input that isn't valid JSON; the message carries the character offset. */
    open class MalformedJsonException(message: String) : IllegalArgumentException(message)

    /** Thrown when the input ends mid-value (interrupted write, partial download). */
    class TruncatedJsonException(message: String) : MalformedJsonException(message)

    private var pos = 0
    private var depth = 0

    /** Parses one whole document; trailing non-whitespace is an error. */
    fun parse(): JsonValue {
        val value = readValue()
        skipWhitespace()
        if (pos < s.length) fail("trailing content after the document")
        return value
    }

    private fun readValue(): JsonValue {
        skipWhitespace()
        if (pos >= s.length) truncated("expected a value")
        return when (val c = s[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonString(readString())
            't' -> readLiteral("true", JsonBool(true))
            'f' -> readLiteral("false", JsonBool(false))
            'n' -> readLiteral("null", JsonNull)
            else ->
                if (c == '-' || c in '0'..'9') readNumber()
                else fail("unexpected character '$c'")
        }
    }

    private fun readObject(): JsonValue {
        enter()
        pos++ // '{'
        // LinkedHashMap: source order is preserved and a duplicate key overwrites in place.
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek("expected '}' or a key") == '}') {
            pos++
            depth--
            return JsonObject(members)
        }
        while (true) {
            skipWhitespace()
            if (peek("expected a key") != '"') fail("object keys must be quoted")
            val key = readString()
            skipWhitespace()
            if (peek("expected ':'") != ':') fail("expected ':' after object key '$key'")
            pos++
            members[key] = readValue()
            skipWhitespace()
            when (peek("expected ',' or '}'")) {
                ',' -> pos++
                '}' -> { pos++; depth--; return JsonObject(members) }
                else -> fail("expected ',' or '}' in object")
            }
        }
    }

    private fun readArray(): JsonValue {
        enter()
        pos++ // '['
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek("expected ']' or a value") == ']') {
            pos++
            depth--
            return JsonArray(items)
        }
        while (true) {
            items.add(readValue())
            skipWhitespace()
            when (peek("expected ',' or ']'")) {
                ',' -> pos++
                ']' -> { pos++; depth--; return JsonArray(items) }
                else -> fail("expected ',' or ']' in array")
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) truncated("unterminated string")
            when (val c = s[pos]) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> { pos++; readEscape(sb) }
                else -> {
                    if (c < ' ') fail("unescaped control character in string")
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun readEscape(sb: StringBuilder) {
        if (pos >= s.length) truncated("string ends after '\\'")
        when (val e = s[pos]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            // Surrogate pairs arrive as two \u escapes; appending each unit in turn rebuilds the
            // code point, so no special casing is needed.
            'u' -> { sb.append(readHex4()); return }
            else -> fail("unknown escape '\\$e'")
        }
        pos++
    }

    private fun readHex4(): Char {
        pos++ // 'u'
        if (pos + 4 > s.length) truncated("truncated \\u escape")
        val hex = s.substring(pos, pos + 4)
        val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
        pos += 4
        return code.toChar()
    }

    private fun readNumber(): JsonValue {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        digits("expected a digit")
        if (pos < s.length && s[pos] == '.') {
            pos++
            digits("expected a digit after '.'")
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            digits("expected a digit in the exponent")
        }
        val raw = s.substring(start, pos)
        return JsonNumber(raw.toDoubleOrNull() ?: fail("bad number '$raw'"))
    }

    private fun digits(what: String) {
        val start = pos
        while (pos < s.length && s[pos] in '0'..'9') pos++
        if (pos == start) {
            if (pos >= s.length) truncated(what) else fail(what)
        }
    }

    private fun readLiteral(word: String, value: JsonValue): JsonValue {
        if (!s.startsWith(word, pos)) {
            if (pos + word.length > s.length) truncated("truncated literal '$word'")
            fail("expected '$word'")
        }
        pos += word.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
    }

    /** The current character, or a truncation error if the input ran out. */
    private fun peek(what: String): Char {
        if (pos >= s.length) truncated(what)
        return s[pos]
    }

    private fun enter() {
        if (++depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
    }

    private fun fail(what: String): Nothing =
        throw MalformedJsonException("Malformed JSON at offset $pos: $what")

    private fun truncated(what: String): Nothing =
        throw TruncatedJsonException("Truncated JSON at offset $pos: $what")

    private companion object {
        const val MAX_DEPTH = 256
    }
}
