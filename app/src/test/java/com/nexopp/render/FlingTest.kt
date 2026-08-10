package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

class FlingTest {

    /** Sum the per-frame steps until the glide stops, at a fixed frame length. */
    private fun glideDistance(vx: Float, vy: Float, dt: Float = 1f / 60f): Pair<Float, Float> {
        val f = Fling()
        f.start(vx, vy)
        var totalX = 0f
        var totalY = 0f
        var frames = 0
        while (f.isMoving && frames < 10_000) {
            val step = f.advance(dt)
            totalX += step.dx
            totalY += step.dy
            frames++
        }
        return totalX to totalY
    }

    @Test fun slowReleaseDoesNotFling() {
        // The view only advances while isMoving, so a below-threshold release travels nothing.
        val f = Fling()
        f.start(10f, 10f) // well below MIN_SPEED_PX
        assertFalse("a gentle lift shouldn't glide", f.isMoving)
        val (dx, dy) = glideDistance(10f, 10f)
        assertEquals(0f, dx, 1e-6f)
        assertEquals(0f, dy, 1e-6f)
    }

    @Test fun fastReleaseGlidesRoughlyVelocityOverDecay() {
        // Total travel of a full exponential decay is v0/k; stopping at MIN_SPEED_PX leaves a little
        // untraveled, so expect just under 3000/5 = 600 px.
        val (dx, dy) = glideDistance(3000f, 0f)
        assertTrue("dx=$dx", dx in 570f..600f)
        assertEquals(0f, dy, 1e-3f)
    }

    @Test fun glideDeceleratesEachFrame() {
        val f = Fling()
        f.start(3000f, 0f)
        var prev = Float.MAX_VALUE
        repeat(20) {
            val d = f.advance(1f / 60f).dx
            assertTrue("steps should shrink: $d !< $prev", d < prev)
            prev = d
        }
    }

    @Test fun distanceIsFrameRateIndependent() {
        val coarse = glideDistance(2500f, 0f, dt = 1f / 30f).first
        val fine = glideDistance(2500f, 0f, dt = 1f / 120f).first
        assertTrue("coarse=$coarse fine=$fine", abs(coarse - fine) < 15f)
    }

    @Test fun stopHaltsTheGlide() {
        val f = Fling()
        f.start(3000f, 3000f)
        assertTrue(f.isMoving)
        f.stop()
        assertFalse(f.isMoving)
        assertEquals(0f, f.advance(1f / 60f).dy, 1e-6f)
    }

    @Test fun nonPositiveDtYieldsNoMotion() {
        val f = Fling()
        f.start(3000f, 0f)
        assertEquals(0f, f.advance(0f).dx, 1e-6f)
        assertEquals(0f, f.advance(-1f).dx, 1e-6f)
        assertTrue("velocity must survive an idle frame", f.isMoving)
    }
}
