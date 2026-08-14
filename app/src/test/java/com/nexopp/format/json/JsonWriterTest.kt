package com.nexopp.format.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emitter coverage for the JSON layer the `.rnote` writer is built on. The load-bearing property is
 * the round trip — whatever [JsonWriter] emits, [JsonReader] must read back identically — because
 * that is the guarantee a saved `.rnote` reopening in Rnote depends on.
 */
class JsonWriterTest {

    private fun parse(text: String): JsonValue = JsonReader(text).parse()

    /** Compare two trees structurally; [JsonValue] has no `equals` of its own. */
    private fun same(a: JsonValue, b: JsonValue): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.members.keys.toList() == b.members.keys.toList() &&
                a.members.all { (k, v) -> same(v, b.members.getValue(k)) }
        a is JsonArray && b is JsonArray ->
            a.items.size == b.items.size && a.items.indices.all { same(a.items[it], b.items[it]) }
        a is JsonString && b is JsonString -> a.value == b.value
        a is JsonNumber && b is JsonNumber -> a.value == b.value
        a is JsonBool && b is JsonBool -> a.value == b.value
        else -> a is JsonNull && b is JsonNull
    }

    @Test
    fun `emits an object with no whitespace between tokens`() {
        val root = jsonObject(
            "version" to JsonString("0.14.2"),
            "size" to jsonNumbers(1.5, -2.0),
            "meta" to jsonObject("locked" to JsonBool(false), "note" to JsonNull),
        )
        assertEquals(
            """{"version":"0.14.2","size":[1.5,-2],"meta":{"locked":false,"note":null}}""",
            root.toJsonString(),
        )
    }

    @Test
    fun `round-trips a brushstroke-shaped payload`() {
        val text = """
            {"brushstroke":{"path":{"start":{"pos":[133.333,133.333],"pressure":1.0},
             "segments":[{"lineto":{"end":{"pos":[146.667,140.0],"pressure":0.817}}}]},
             "style":{"smooth":{"stroke_width":1.6,"stroke_color":{"r":0.2,"g":0.2,"b":0.8,"a":1.0},
             "fill_color":null,"pressure_curve":"linear","line_style":"solid","line_cap":"straight"}}}}
        """
        val parsed = parse(text)
        assertTrue(same(parsed, parse(parsed.toJsonString())))
    }

    @Test
    fun `writes an integral double without a fraction and keeps a real one`() {
        // Rnote types pixel_width, font_weight, t and chrono_counter as integers; `3.0` would not
        // deserialise into them.
        assertEquals("[2,700,1122.52,-0.5]", jsonNumbers(2.0, 700.0, 1122.52, -0.5).toJsonString())
    }

    @Test
    fun `escapes only what the grammar requires`() {
        // The solidus and every non-ASCII character go through verbatim, as serde_json writes them.
        val source = "q\" b\\ s/ \b\u000C\n\r\t\u0001 \u00E9\uD83D\uDE00"
        val text = JsonString(source).toJsonString()
        assertEquals("\"q\\\" b\\\\ s/ \\b\\f\\n\\r\\t\\u0001 \u00E9\uD83D\uDE00\"", text)
        assertEquals(source, parse(text).str())
    }

    @Test
    fun `refuses the numbers JSON cannot spell`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { JsonNumber(value).toJsonString() }
        }
    }
}
