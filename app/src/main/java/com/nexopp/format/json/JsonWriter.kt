package com.nexopp.format.json

/**
 * The emitter half of the JSON layer: a [JsonValue] tree back into the **compact, whitespace-free**
 * text `serde_json` writes, which is the only shape a `.rnote` file ever contains (see
 * `docs/architecture.md`, "The `.rnote` format — container & serialisation"). The mirror of
 * [JsonReader], and dependency-free for the same reason: the writer runs on-device and under JVM
 * unit tests unchanged.
 *
 * Numbers are the one place a plain `Double.toString()` would betray the format — Rnote's serde
 * structs hold integers for `pixel_width`, `font_weight`, `t` and `chrono_counter`, and `3.0` where
 * `3` is expected reads as a float on the other side. [writeNumber] therefore emits the integral
 * form whenever the value *is* integral, which is what serde does for those fields and is
 * indistinguishable from `3.0` for the fields that are genuinely floats.
 */
object JsonWriter {

    /**
     * Beyond this magnitude a `Double` can no longer represent every integer, so the integral
     * shortcut in [writeNumber] would print a rounded value as if it were exact.
     */
    private const val MAX_EXACT_INTEGER = 1e15

    /**
     * Emit one value as compact JSON.
     *
     * @param value The tree to serialise.
     * @return The document text, with no whitespace between tokens.
     * @throws IllegalArgumentException If a [JsonNumber] holds NaN or an infinity, neither of which
     *   JSON can spell.
     */
    fun write(value: JsonValue): String = StringBuilder().also { writeValue(value, it) }.toString()

    /** Dispatch one node onto its own emitter; the recursion depth matches the tree's. */
    private fun writeValue(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonObject -> writeObject(value, out)
            is JsonArray -> writeArray(value, out)
            is JsonString -> writeString(value.value, out)
            is JsonNumber -> writeNumber(value.value, out)
            is JsonBool -> out.append(if (value.value) "true" else "false")
            JsonNull -> out.append("null")
        }
    }

    /** `{"k":v,…}` in member order — a [JsonObject] keeps source order, so round trips are stable. */
    private fun writeObject(value: JsonObject, out: StringBuilder) {
        out.append('{')
        var first = true
        for ((key, member) in value.members) {
            if (!first) out.append(',')
            first = false
            writeString(key, out)
            out.append(':')
            writeValue(member, out)
        }
        out.append('}')
    }

    /** `[a,b,…]` in order. */
    private fun writeArray(value: JsonArray, out: StringBuilder) {
        out.append('[')
        for ((index, item) in value.items.withIndex()) {
            if (index > 0) out.append(',')
            writeValue(item, out)
        }
        out.append(']')
    }

    /**
     * A quoted string, escaping only what RFC 8259 requires: the quote, the backslash and the C0
     * controls. `/` and non-ASCII characters go through verbatim, exactly as `serde_json` writes
     * them — the file is UTF-8, so escaping them would only make it longer.
     */
    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /**
     * A number, integral when the value is: `3.0` prints as `3` so the fields Rnote types as
     * integers (`pixel_width`, `font_weight`, `t`, `chrono_counter`, `version`) are spelled the way
     * its deserialiser expects, while a genuine float keeps every digit `Double.toString` needs to
     * round-trip.
     */
    private fun writeNumber(value: Double, out: StringBuilder) {
        require(value.isFinite()) { "JSON has no literal for $value" }
        val integral = value == Math.floor(value) && Math.abs(value) < MAX_EXACT_INTEGER
        out.append(if (integral) value.toLong().toString() else value.toString())
    }
}

/**
 * Emit this value as compact JSON — the call-site-friendly spelling of [JsonWriter.write].
 *
 * @return The document text, with no whitespace between tokens.
 */
fun JsonValue.toJsonString(): String = JsonWriter.write(this)

/**
 * Build a [JsonObject] from its members in the order given, which is the order they are written in.
 *
 * @param pairs The members, key to value.
 * @return The object node.
 */
fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonObject =
    JsonObject(linkedMapOf(*pairs))

/**
 * Build a [JsonArray] from its items.
 *
 * @param items The items in order.
 * @return The array node.
 */
fun jsonArray(vararg items: JsonValue): JsonArray = JsonArray(items.toList())

/**
 * Build a [JsonArray] of numbers — the shape every Rnote coordinate pair and affine matrix takes.
 *
 * @param values The numbers in order.
 * @return The array node.
 */
fun jsonNumbers(vararg values: Double): JsonArray = JsonArray(values.map { JsonNumber(it) })
