package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityEstimatorTest {

    @Test fun constantSpeedIsRecovered() {
        val e = VelocityEstimator()
        // Move 100 px every 10 ms along y => 10 px/ms => 10_000 px/s.
        for (i in 0..10) e.add(i * 10L, 0f, i * 100f)
        val v = e.velocity()
        assertEquals(0f, v.vx, 1e-3f)
        assertEquals(10_000f, v.vy, 50f)
    }

    @Test fun tooFewSamplesIsZero() {
        val e = VelocityEstimator()
        assertEquals(Velocity(0f, 0f), e.velocity())
        e.add(0L, 5f, 5f)
        assertEquals(Velocity(0f, 0f), e.velocity())
    }

    @Test fun onlyRecentWindowCounts() {
        // A long slow lead-in then a fast final flick: the estimate reflects the recent window,
        // not the whole gesture.
        val e = VelocityEstimator(windowMs = 50L)
        e.add(0L, 0f, 0f)
        e.add(500L, 0f, 50f)      // slow drift over half a second (ancient, outside the window)
        e.add(530L, 0f, 350f)     // 300 px in the last 30 ms => 10_000 px/s
        val v = e.velocity()
        assertTrue("vy=${v.vy}", v.vy in 9_000f..11_000f)
    }

    @Test fun resetClearsHistory() {
        val e = VelocityEstimator()
        e.add(0L, 0f, 0f)
        e.add(10L, 0f, 100f)
        e.reset()
        assertEquals(Velocity(0f, 0f), e.velocity())
    }

    @Test fun zeroTimeSpanIsZeroNotInfinite() {
        val e = VelocityEstimator()
        e.add(5L, 0f, 0f)
        e.add(5L, 0f, 200f) // same timestamp — must not divide by zero
        assertEquals(Velocity(0f, 0f), e.velocity())
    }

    @Test fun diagonalFlickHasBothComponents() {
        val e = VelocityEstimator()
        for (i in 0..5) e.add(i * 10L, i * 100f, i * 200f)
        val v = e.velocity()
        assertTrue("vx=${v.vx}", v.vx in 9_000f..11_000f)
        assertTrue("vy=${v.vy}", v.vy in 19_000f..21_000f)
    }
}
