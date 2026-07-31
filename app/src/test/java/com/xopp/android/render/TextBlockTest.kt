package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockTest {

    @Test fun splitsOnNewlinesPreservingEmptyLines() {
        assertEquals(listOf("a", "b"), TextBlock.lines("a\nb"))
        assertEquals(listOf("a", "", "b"), TextBlock.lines("a\n\nb"))
        // A trailing newline yields an empty final line, matching how the box is drawn.
        assertEquals(listOf("a", ""), TextBlock.lines("a\n"))
        assertEquals(listOf("only"), TextBlock.lines("only"))
    }

    @Test fun firstBaselineSitsOneAscentBelowTheTop() {
        // Ascent is negative (Android convention); baseline = top - ascent = top + |ascent|.
        val b = TextBlock.baselines(lineCount = 1, topPx = 100f, ascentPx = -12f, lineHeightPx = 16f)
        assertEquals(listOf(112f), b)
    }

    @Test fun eachLineAdvancesByLineHeight() {
        val b = TextBlock.baselines(lineCount = 3, topPx = 100f, ascentPx = -12f, lineHeightPx = 16f)
        assertEquals(listOf(112f, 128f, 144f), b)
    }

    @Test fun zeroLinesYieldNoBaselines() {
        assertEquals(emptyList<Float>(), TextBlock.baselines(0, 100f, -12f, 16f))
    }
}
