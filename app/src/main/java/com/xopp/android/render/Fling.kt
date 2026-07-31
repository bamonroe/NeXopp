package com.xopp.android.render

import kotlin.math.exp
import kotlin.math.hypot

/**
 * Pure two-axis fling kinematics: after a pan is released with a velocity, [advance] decays that
 * velocity and yields the px the content should glide each frame, until [isMoving] drops false.
 * Deliberately Android-free so it unit-tests on the JVM (see `FlingTest`); the [DrawingSurfaceView]
 * feeds it a release velocity and drives [advance] from a `Choreographer` frame loop.
 *
 * Model: `v(t) = v0·e^(−k·t)`. Over a frame of length `dt` the exact distance is the integral
 * `(v − v')/k` where `v'` is the post-frame velocity, so the glide is identical whatever the frame
 * rate (a dropped frame just takes a bigger, correctly-sized step).
 */
class Fling(
    private val decayPerSecond: Float = DECAY_PER_SECOND,
    private val minSpeedPx: Float = MIN_SPEED_PX,
) {
    private var vx = 0f
    private var vy = 0f

    /** True while the fling still carries enough speed to keep gliding. */
    val isMoving: Boolean get() = hypot(vx, vy) >= minSpeedPx

    /** Seed the fling with a release velocity (px/s). Below [minSpeedPx] it simply won't move. */
    fun start(velocityXPx: Float, velocityYPx: Float) {
        vx = velocityXPx
        vy = velocityYPx
    }

    /** Halt the glide immediately (e.g. the user touched down again). */
    fun stop() {
        vx = 0f
        vy = 0f
    }

    /**
     * Advance the glide by [dtSeconds] and return the px to scroll this frame. A non-positive dt
     * (or a stopped fling) yields no motion.
     */
    fun advance(dtSeconds: Float): FlingStep {
        if (dtSeconds <= 0f) return FlingStep(0f, 0f)
        val factor = exp(-decayPerSecond * dtSeconds)
        val dx = (vx - vx * factor) / decayPerSecond
        val dy = (vy - vy * factor) / decayPerSecond
        vx *= factor
        vy *= factor
        return FlingStep(dx, dy)
    }

    companion object {
        /** Exponential velocity decay per second — higher stops the glide sooner. */
        const val DECAY_PER_SECOND = 5f
        /** Below this speed (px/s) the glide is considered finished. */
        const val MIN_SPEED_PX = 60f
    }
}

/** The px delta a single [Fling.advance] frame contributes, per axis. */
data class FlingStep(val dx: Float, val dy: Float)
