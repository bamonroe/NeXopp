package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Flattening of `ranged_text_attributes` into disjoint runs, and the whole-string uniformity test. */
class RnoteTextRunsTest {

    private fun attrs(json: String) = JsonReader(json).parse()

    @Test
    fun `no attributes gives one run over the whole text`() {
        assertEquals(listOf(TextRun(0, 5, emptyMap())), flattenRuns(null, 5))
        assertEquals(listOf(TextRun(0, 5, emptyMap())), flattenRuns(attrs("[]"), 5))
    }

    @Test
    fun `empty text gives no runs`() {
        assertEquals(emptyList<TextRun>(), flattenRuns(null, 0))
    }

    @Test
    fun `two separated ranges give the gap its own bare run`() {
        val runs = flattenRuns(
            attrs(
                """[{"range":[2,4],"attribute":{"underline":true}},
                    {"range":[6,8],"attribute":{"font_size":12.0}}]"""
            ),
            10,
        )
        assertEquals(
            listOf(
                TextRun(0, 2, emptyMap()),
                TextRun(2, 4, mapOf("underline" to "true")),
                TextRun(4, 6, emptyMap()),
                TextRun(6, 8, mapOf("font_size" to "12")),
                TextRun(8, 10, emptyMap()),
            ),
            runs,
        )
    }

    @Test
    fun `a later entry wins on the bytes it shares with an earlier one`() {
        val runs = flattenRuns(
            attrs(
                """[{"range":[0,6],"attribute":{"font_family":"Serif"}},
                    {"range":[3,6],"attribute":{"font_family":"Sans"}}]"""
            ),
            6,
        )
        assertEquals(
            listOf(
                TextRun(0, 3, mapOf("font_family" to "Serif")),
                TextRun(3, 6, mapOf("font_family" to "Sans")),
            ),
            runs,
        )
    }

    @Test
    fun `malformed reversed and out-of-bounds ranges are ignored`() {
        val runs = flattenRuns(
            attrs(
                """[{"range":[4,2],"attribute":{"underline":true}},
                    {"range":[0,99],"attribute":{"underline":true}},
                    {"range":[1],"attribute":{"underline":true}},
                    {"range":[0,4]}]"""
            ),
            4,
        )
        assertEquals(listOf(TextRun(0, 4, emptyMap())), runs)
    }

    @Test
    fun `uniformAttribute answers only when every run agrees`() {
        val whole = flattenRuns(attrs("""[{"range":[0,4],"attribute":{"font_weight":700}}]"""), 4)
        assertEquals("700", uniformAttribute(whole, "font_weight"))
        assertNull(uniformAttribute(whole, "underline"))

        val partial = flattenRuns(attrs("""[{"range":[0,2],"attribute":{"font_weight":700}}]"""), 4)
        assertNull(uniformAttribute(partial, "font_weight"))
        assertNull(uniformAttribute(emptyList(), "font_weight"))
    }

    @Test
    fun `a composite value renders structurally`() {
        val runs = flattenRuns(
            attrs("""[{"range":[0,2],"attribute":{"text_color":{"r":1.0,"g":0.0,"b":0.5,"a":1.0}}}]"""),
            2,
        )
        assertEquals("{r:1,g:0,b:0.5,a:1}", runs.single().attrs["text_color"])
    }
}
