package com.nexopp.format

import org.junit.Assert.assertEquals
import org.junit.Test

class PageRangeTest {

    @Test fun blankSpecMeansEveryPage() {
        assertEquals(listOf(0, 1, 2, 3, 4), PageRange.parse("", 5))
        assertEquals(listOf(0, 1, 2, 3, 4), PageRange.parse("   ", 5))
    }

    @Test fun singlePageIsZeroBased() {
        assertEquals(listOf(0), PageRange.parse("1", 5))
        assertEquals(listOf(4), PageRange.parse("5", 5))
    }

    @Test fun commaListKeepsEachPage() {
        assertEquals(listOf(0, 2, 4), PageRange.parse("1,3,5", 5))
        assertEquals(listOf(0, 2, 4), PageRange.parse(" 1 , 3 , 5 ", 5))
    }

    @Test fun rangeExpandsInclusively() {
        assertEquals(listOf(0, 1, 2), PageRange.parse("1-3", 5))
        assertEquals(listOf(1, 2, 3), PageRange.parse("2 - 4", 5))
    }

    @Test fun openEndedRangeRunsToTheLastPage() {
        assertEquals(listOf(5, 6, 7), PageRange.parse("6-", 8))
        assertEquals(listOf(0, 1, 2), PageRange.parse("1-", 3))
    }

    @Test fun reversedRangeIsReadForwards() {
        assertEquals(listOf(1, 2, 3, 4), PageRange.parse("5-2", 8))
    }

    @Test fun outOfBoundsPagesAreDroppedNotRejected() {
        assertEquals(listOf(0, 1), PageRange.parse("1,2,9", 2))
        assertEquals(emptyList<Int>(), PageRange.parse("0", 5))
        assertEquals(emptyList<Int>(), PageRange.parse("12", 5))
        // The range is clipped to the document rather than thrown away wholesale.
        assertEquals(listOf(3, 4), PageRange.parse("4-40", 5))
        assertEquals(listOf(0, 1), PageRange.parse("0-2", 5))
    }

    @Test fun duplicatesAndOverlapsCollapse() {
        assertEquals(listOf(0, 1, 2), PageRange.parse("1,1,2,1-3", 5))
        assertEquals(listOf(0, 1, 2, 3), PageRange.parse("3-4,1-2", 5))
    }

    @Test fun garbageTokensAreIgnored() {
        assertEquals(listOf(0, 2), PageRange.parse("1,abc,3", 5))
        assertEquals(listOf(0), PageRange.parse("1,,,", 5))
        assertEquals(emptyList<Int>(), PageRange.parse("abc", 5))
        assertEquals(emptyList<Int>(), PageRange.parse("x-y", 5))
        assertEquals(emptyList<Int>(), PageRange.parse("-", 5))
    }

    @Test fun emptyDocumentYieldsNothing() {
        assertEquals(emptyList<Int>(), PageRange.parse("", 0))
        assertEquals(emptyList<Int>(), PageRange.parse("1-3", 0))
    }
}
