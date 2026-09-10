package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** Counting confirmed taps into a run: what continues one, what breaks it, and where it wraps. */
class MultiTapDetectorTest {

    private fun detector(maxTaps: Int = 3) =
        MultiTapDetector(slopPx = 10f, windowMs = 300L, maxTaps = maxTaps)

    @Test
    fun `taps in the same place inside the window build a run`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(2, d.tap(1200L, 52f, 48f))
    }

    @Test
    fun `a tap past the window starts a fresh run`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(1, d.tap(1400L, 50f, 50f))
    }

    @Test
    fun `a tap past the slop starts a fresh run, however quick`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(1, d.tap(1050L, 90f, 50f))
    }

    @Test
    fun `the run wraps at maxTaps, so a fourth tap opens a new one`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(2, d.tap(1100L, 50f, 50f))
        assertEquals(3, d.tap(1200L, 50f, 50f))
        assertEquals(1, d.tap(1300L, 50f, 50f))
    }

    @Test
    fun `a two-tap detector wraps at the double-tap`() {
        val d = detector(maxTaps = 2)
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(2, d.tap(1100L, 50f, 50f))
        assertEquals(1, d.tap(1200L, 50f, 50f))
    }

    @Test
    fun `reset abandons the run in progress`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        d.reset()
        assertEquals(1, d.tap(1100L, 50f, 50f))
    }

    @Test
    fun `a widened window keeps taps together that the default would have split`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(1, d.tap(1500L, 50f, 50f))
        d.windowMs = 800L
        assertEquals(2, d.tap(2000L, 50f, 50f))
    }

    /** Event time can only move forwards; a backwards jump is a stale tap, not a fast one. */
    @Test
    fun `a tap earlier than the last one starts a fresh run`() {
        val d = detector()
        assertEquals(1, d.tap(1000L, 50f, 50f))
        assertEquals(1, d.tap(900L, 50f, 50f))
    }
}
