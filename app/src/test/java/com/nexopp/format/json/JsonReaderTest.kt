package com.nexopp.format.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar coverage for the hand-rolled JSON layer the `.rnote` reader is built on. */
class JsonReaderTest {

    private fun parse(text: String): JsonValue = JsonReader(text).parse()

    @Test
    fun `parses a nested object and array`() {
        val root = parse(
            """
            { "name": "sheet", "size": [1.5, -2, 3e2],
              "meta": { "locked": false, "note": null } }
            """
        )
        assertEquals("sheet", root.obj("name")?.str())
        assertEquals(listOf(1.5, -2.0, 300.0), root.obj("size")?.arr()?.map { it.num() })
        assertEquals(false, root.path("meta", "locked")?.bool())
        assertTrue(root.path("meta", "note")!!.isNull())
        // A missing key is null, and so is a type mismatch.
        assertNull(root.path("meta", "absent"))
        assertNull(root.obj("name")?.arr())
    }

    @Test
    fun `decodes every escape sequence including a surrogate pair`() {
        // Written with doubled backslashes so the parser, not Kotlin, does the decoding.
        val root = parse("\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u0041\\uD83D\\uDE00\"")
        assertEquals("q\" b\\ s/ \b\u000C\n\r\t A\uD83D\uDE00", root.str())
        assertEquals(2, root.str()!!.takeLast(2).length)
        assertEquals(1L, root.str()!!.codePoints().filter { it == 0x1F600 }.count())
    }

    @Test
    fun `parses numbers with fractions exponents and signs`() {
        val nums = parse("[0, -0.5, 12.25, 1e3, 1E+3, 2.5e-2, -7E2]").arr()!!.map { it.num() }
        assertEquals(listOf(0.0, -0.5, 12.25, 1000.0, 1000.0, 0.025, -700.0), nums)
    }

    @Test
    fun `parses empty containers and whitespace-only padding`() {
        assertEquals(0, parse("  {  }  ").let { (it as JsonObject).members.size })
        assertEquals(0, parse("\n[\t]\r\n").arr()!!.size)
    }

    @Test
    fun `keeps member order and lets the last duplicate key win`() {
        val obj = parse("""{"a":1,"b":2,"a":3}""") as JsonObject
        assertEquals(listOf("a", "b"), obj.members.keys.toList())
        assertEquals(3.0, obj.members["a"]!!.num()!!, 0.0)
    }

    @Test
    fun `rejects malformed input`() {
        for (bad in listOf("""{"a":1,}""", """{a:1}""", "[1,,2]", """{"a" 1}""", "[1] x", """{"a":1"b":2}""")) {
            try {
                parse(bad)
                throw AssertionError("expected a parse failure for: $bad")
            } catch (e: JsonReader.MalformedJsonException) {
                assertTrue(e.message!!.contains("offset"))
            }
        }
    }

    @Test
    fun `reports truncated input distinctly`() {
        for (cut in listOf("""{"a": """, """{"a": "unterm""", "[1, 2", "tru", "-")) {
            try {
                parse(cut)
                throw AssertionError("expected a truncation failure for: $cut")
            } catch (e: JsonReader.TruncatedJsonException) {
                assertTrue(e.message!!.startsWith("Truncated JSON"))
            }
        }
    }

    @Test
    fun `rejects nesting past the depth limit instead of overflowing the stack`() {
        val deep = "[".repeat(300) + "]".repeat(300)
        try {
            parse(deep)
            throw AssertionError("expected a depth-limit failure")
        } catch (e: JsonReader.MalformedJsonException) {
            assertTrue(e.message!!.contains("nesting deeper than 256"))
        }
    }

    @Test
    fun `accepts nesting up to the depth limit`() {
        val ok = "[".repeat(256) + "]".repeat(256)
        assertEquals(1, parse(ok).arr()!!.size)
    }
}
