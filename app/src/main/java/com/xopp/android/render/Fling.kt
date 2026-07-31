package com.xopp.android.render

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.round

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

/**
 * The user-adjustable momentum strength: a single continuous factor that scales the release velocity
 * fed into a [Fling] (see `DrawingSurfaceView.flingStrength`). [OFF] (0) disables the glide entirely,
 * [NORMAL] (1.0, the default) flings at the as-released speed, and values up to [MAX] glide
 * progressively further before stalling. The lower bound is always a dead stop, whatever the maximum.
 */
object Momentum {
    /** No carried momentum — a released pan stops dead. */
    const val OFF = 0f

    /** Glides at the as-released speed — the default and historical behaviour. */
    const val NORMAL = 1.0f

    /** The strongest glide the control allows — a long, far-reaching coast. */
    const val MAX = 10.0f

    /** Granularity the strength snaps to (the persisted precision and the slider's effective grid). */
    const val STEP = 0.1f

    /** Clamp an arbitrary strength into the valid [OFF]..[MAX] range. */
    fun coerce(value: Float): Float = value.coerceIn(OFF, MAX)

    /** Snap to the [STEP] grid and clamp — the continuous slider rounds through this so the stored
     * value stays a clean multiple of [STEP] even without discrete slider stops. */
    fun snap(value: Float): Float = (round(value / STEP) * STEP).coerceIn(OFF, MAX)
}

/**
 * The user-adjustable panning gain: a single continuous factor scaling how far the document moves per
 * unit of finger/stylus pan travel (see `DrawingSurfaceView.panSensitivity`). [OFF] (0) freezes the
 * document under a pan, [NORMAL] (1.0, the default) tracks the finger one-to-one, values below 1 pan
 * slower than the input and values up to [MAX] pan faster. The gain also scales the released velocity
 * so a fling glides at the same visual rate as the pan that launched it.
 */
object PanSensitivity {
    /** Panning input produces no document movement. */
    const val OFF = 0f

    /** One-to-one: the document tracks the finger exactly — the default. */
    const val NORMAL = 1.0f

    /** The fastest pan the control allows — the document races ahead of the finger. */
    const val MAX = 4.0f

    /** Granularity the gain snaps to (the persisted precision and the slider's effective grid). */
    const val STEP = 0.1f

    /** Clamp an arbitrary gain into the valid [OFF]..[MAX] range. */
    fun coerce(value: Float): Float = value.coerceIn(OFF, MAX)

    /** Snap to the [STEP] grid and clamp — mirrors [Momentum.snap] for the continuous slider. */
    fun snap(value: Float): Float = (round(value / STEP) * STEP).coerceIn(OFF, MAX)
}
