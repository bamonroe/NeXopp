package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [LatexParser] — no Android needed. These pin down the shape of the
 * expression tree the renderer relies on: superscripts, fractions, roots, Unicode command
 * mapping, graceful degradation of unknown commands, and empty input.
 */
class LatexParserTest {

    @Test fun emptyStringYieldsEmptyRow() {
        assertEquals(Row(emptyList()), LatexParser.parse(""))
    }

    @Test fun superscriptOnSingleToken() {
        assertEquals(
            Row(listOf(Superscript(SymbolRun("x"), SymbolRun("2")))),
            LatexParser.parse("x^2"),
        )
    }

    @Test fun subscriptOnSingleToken() {
        assertEquals(
            Row(listOf(Subscript(SymbolRun("a"), SymbolRun("1")))),
            LatexParser.parse("a_1"),
        )
    }

    @Test fun superAndSubscriptMergeIntoSubSup() {
        assertEquals(
            Row(listOf(SubSup(SymbolRun("x"), SymbolRun("i"), SymbolRun("2")))),
            LatexParser.parse("x^2_i"),
        )
    }

    @Test fun bracedScriptGroupsMultipleTokens() {
        assertEquals(
            Row(listOf(Superscript(SymbolRun("e"), Row(listOf(SymbolRun("2"), SymbolRun("x")))))),
            LatexParser.parse("e^{2x}"),
        )
    }

    @Test fun fractionHasRowChildren() {
        assertEquals(
            Row(listOf(Fraction(Row(listOf(SymbolRun("a"))), Row(listOf(SymbolRun("b")))))),
            LatexParser.parse("\\frac{a}{b}"),
        )
    }

    @Test fun sqrtWrapsItsGroup() {
        assertEquals(
            Row(listOf(Sqrt(Row(listOf(SymbolRun("x")))))),
            LatexParser.parse("\\sqrt{x}"),
        )
    }

    @Test fun greekCommandMapsToUnicodeGlyph() {
        assertEquals(Row(listOf(SymbolRun("α"))), LatexParser.parse("\\alpha"))
    }

    @Test fun operatorCommandsMapToUnicode() {
        assertEquals(
            Row(listOf(SymbolRun("α"), SymbolRun("+"), SymbolRun("β"))),
            LatexParser.parse("\\alpha + \\beta"),
        )
    }

    @Test fun nestedFractionWithScriptAndRoot() {
        val expected = Row(
            listOf(
                Fraction(
                    Row(listOf(Superscript(SymbolRun("x"), SymbolRun("2")))),
                    Row(listOf(Sqrt(Row(listOf(SymbolRun("y")))))),
                ),
            ),
        )
        assertEquals(expected, LatexParser.parse("\\frac{x^2}{\\sqrt{y}}"))
    }

    @Test fun unknownCommandDegradesToItsName() {
        assertEquals(Row(listOf(SymbolRun("foo"))), LatexParser.parse("\\foo"))
    }

    @Test fun malformedInputDoesNotThrow() {
        // Unbalanced braces, dangling scripts, trailing backslash — all must parse to *something*.
        for (src in listOf("{", "}", "^", "_", "\\", "\\frac{a}", "x^", "{{a}")) {
            val node = LatexParser.parse(src)
            assertTrue("expected a Row for \"$src\"", node is Row)
        }
    }
}
