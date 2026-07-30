package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundGridTest {

    @Test fun interiorLinesAreEvenlySpaced() {
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), BackgroundGrid.lines(50.0, 10.0))
    }

    @Test fun endpointIsExclusive() {
        // A line exactly at the extent (50) is a page edge, not an interior rule.
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), BackgroundGrid.lines(50.0, 10.0))
        assertTrue(BackgroundGrid.lines(50.0, 10.0).none { it >= 50.0 })
    }

    @Test fun nonPositiveInputsYieldNoLines() {
        assertTrue(BackgroundGrid.lines(50.0, 0.0).isEmpty())
        assertTrue(BackgroundGrid.lines(50.0, -5.0).isEmpty())
        assertTrue(BackgroundGrid.lines(0.0, 10.0).isEmpty())
    }
}
