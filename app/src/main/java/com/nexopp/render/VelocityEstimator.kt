package com.nexopp.render

/**
 * Estimates pointer velocity from a trailing window of position samples — a small, deterministic
 * stand-in for `android.view.VelocityTracker` (which returns nothing for the synthetic events used
 * in emulator tests, and is finicky on a real last-flick sample). Fed the pan focus on each move;
 * on release [velocity] is the average px/s over the most recent [windowMs]. Pure and Android-free,
 * so it unit-tests on the JVM (`VelocityEstimatorTest`).
 */
class VelocityEstimator(private val windowMs: Long = 80L) {
    private val ts = ArrayList<Long>()
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()

    /** Drop all samples (call when a gesture starts, or its pointer set changes). */
    fun reset() {
        ts.clear(); xs.clear(); ys.clear()
    }

    /** Record a focus sample at event time [tMs] (ms). */
    fun add(tMs: Long, x: Float, y: Float) {
        ts.add(tMs); xs.add(x); ys.add(y)
    }

    /**
     * Average velocity (px/s) over the samples within [windowMs] of the newest one. Zero if there
     * are fewer than two samples or they span no time.
     */
    fun velocity(): Velocity {
        val n = ts.size
        if (n < 2) return Velocity(0f, 0f)
        val last = ts[n - 1]
        // Walk back to the oldest sample still inside the window — that's the baseline for the average.
        var i = n - 1
        while (i > 0 && last - ts[i - 1] <= windowMs) i--
        val dt = (last - ts[i]) / 1000f
        if (dt <= 0f) return Velocity(0f, 0f)
        return Velocity((xs[n - 1] - xs[i]) / dt, (ys[n - 1] - ys[i]) / dt)
    }
}

/** A 2D velocity in px/s. */
data class Velocity(val vx: Float, val vy: Float)
