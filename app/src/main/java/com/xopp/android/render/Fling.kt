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
 * The user-adjustable momentum strength and the velocity→coast response that gives a pan its
 * throw-a-rock feel. [seed] maps a release velocity to the velocity fed into a [Fling], shaping how
 * hard a fast flick is rewarded via a selectable [MomentumCurve] (linear … exponential). [OFF] (0)
 * disables the glide entirely, [NORMAL] (1.0, the default) coasts at ~the release speed for a moderate
 * ([REFERENCE_SPEED_PX]) flick whatever the curve, and values up to [MAX] stretch every coast farther.
 * The lower bound is always a dead stop.
 */
object Momentum {
    /** No carried momentum — a released pan stops dead. */
    const val OFF = 0f

    /** The reference feel: a moderate ([REFERENCE_SPEED_PX]) flick coasts at ~its release speed. */
    const val NORMAL = 1.0f

    /** The strongest glide the control allows — a long, far-reaching coast. */
    const val MAX = 10.0f

    /** Granularity the strength snaps to (the persisted precision and the slider's effective grid). */
    const val STEP = 0.1f

    /**
     * The release speed (px/s) at which momentum coasts linearly — a moderate swipe. Below it the
     * quadratic [seed] response damps small/slow flicks toward nothing; above it fast swipes coast
     * disproportionately farther. The pivot of the whole velocity→coast curve.
     */
    const val REFERENCE_SPEED_PX = 2000f

    /** Clamp an arbitrary strength into the valid [OFF]..[MAX] range. */
    fun coerce(value: Float): Float = value.coerceIn(OFF, MAX)

    /** Snap to the [STEP] grid and clamp — the continuous slider rounds through this so the stored
     * value stays a clean multiple of [STEP] even without discrete slider stops. */
    fun snap(value: Float): Float = (round(value / STEP) * STEP).coerceIn(OFF, MAX)

    /**
     * Map a pan release velocity ([vx]/[vy], px/s) to the velocity to seed a [Fling] with, at the given
     * [strength] and response [curve]. Direction is preserved; the magnitude is
     * `strength · REFERENCE_SPEED_PX · curve.factor(speed / REFERENCE_SPEED_PX)`. Because every curve
     * passes through `factor(1) = 1`, a flick at [REFERENCE_SPEED_PX] and [NORMAL] always seeds at its
     * own speed (the shared reference point); the curve only sets how the coast falls off below that
     * speed and grows above it. Zero at [OFF] or no motion.
     */
    fun seed(vx: Float, vy: Float, strength: Float, curve: MomentumCurve): Pair<Float, Float> {
        val speed = hypot(vx, vy)
        if (speed <= 0f || strength <= OFF) return 0f to 0f
        val magnitude = strength * REFERENCE_SPEED_PX * curve.factor(speed / REFERENCE_SPEED_PX)
        val rescale = magnitude / speed // stretch the release vector to the shaped magnitude, direction intact
        return vx * rescale to vy * rescale
    }
}

/**
 * The velocity→coast response shape for momentum (see [Momentum.seed]). Each curve maps a *normalized*
 * release speed `u = speed / Momentum.REFERENCE_SPEED_PX` to a unitless coast [factor], and every curve
 * is pinned to `factor(0) = 0` and `factor(1) = 1` so switching curves keeps the reference feel and the
 * strength scale comparable — only the aggressiveness of the reward for a fast flick changes.
 */
enum class MomentumCurve(val label: String) {
    /** Coast grows in step with flick speed — the gentlest, most even spread. */
    LINEAR("Linear") { override fun factor(u: Float): Float = u },

    /** Coast grows with the square of flick speed — the balanced default. */
    QUADRATIC("Quadratic") { override fun factor(u: Float): Float = u * u },

    /** Coast grows with the cube of flick speed — small flicks damped hard, fast ones fly. */
    CUBIC("Cubic") { override fun factor(u: Float): Float = u * u * u },

    /** Coast grows exponentially past the reference speed — the most aggressive reward for a fast flick. */
    EXPONENTIAL("Exponential") {
        override fun factor(u: Float): Float = (exp(EXP_STEEPNESS * u) - 1f) / (exp(EXP_STEEPNESS) - 1f)
    };

    /** Unitless coast multiplier for normalized release speed [u]; `factor(0)=0`, `factor(1)=1`. */
    abstract fun factor(u: Float): Float

    companion object {
        /** Steepness of [EXPONENTIAL]: larger = sharper take-off above the reference speed. */
        const val EXP_STEEPNESS = 2.5f
    }
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
