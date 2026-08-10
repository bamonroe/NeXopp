package com.nexopp.render

import android.content.Context
import android.view.Choreographer
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Momentum scrolling: a released pan keeps gliding, decelerating, until it stalls or hits a bound.
 *
 * This owns the whole loop — tracking the pan's focus samples, latching a release velocity, shaping
 * it through [Momentum], and driving one [Choreographer] frame at a time — so [DrawingSurfaceView]
 * only has to say where the pan is ([track]) and that it ended ([launch]). The host supplies the two
 * things the driver can't know: how to move the viewport ([scrollBy], which reports whether it
 * actually moved, so a glide pinned at a bound stops) and how to paint a frame.
 *
 * The physics itself lives in [Fling] and [Momentum]; those are pure and unit-tested.
 */
internal class MomentumDriver(
    context: Context,
    private val choreographer: Choreographer,
    /** True when there is anywhere left to scroll; a glide is pointless otherwise. */
    private val canScroll: () -> Boolean,
    /** Scroll by one step, returning true if the viewport actually moved. */
    private val scrollBy: (Float, Float) -> Boolean,
    /** Paint a glide frame now — the driver is already inside a frame dispatch when it calls this. */
    private val paintFrame: () -> Unit,
    /** Drop any repaint already queued for the coming vsync, so one vsync posts one buffer. */
    private val cancelQueuedPaint: () -> Unit,
) {

    /** Scales the release velocity fed into a fling; 1 = as-flung, 0 disables momentum. */
    var strength = Momentum.NORMAL
    /** The velocity→coast response shape for momentum (see [MomentumCurve]). */
    var curve = MomentumCurve.QUADRATIC

    private val fling = Fling()
    private val velocityEstimator = VelocityEstimator()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    /** True while a glide is in flight — the view suppresses ordinary repaints during one. */
    var isFlinging = false
        private set
    private var lastFrameNanos = 0L

    /** Release velocity of the just-ended pan (content-space px/s), captured on the final ACTION_UP. */
    private var releaseVx = 0f
    private var releaseVy = 0f

    /** True when the last release was a real flick — i.e. something would coast. */
    val hasRelease: Boolean get() = releaseVx != 0f || releaseVy != 0f

    /**
     * Feed the pan's current focus (view px) to the velocity estimate.
     * @param eventTimeMs Event timestamp in milliseconds.
     * @param x Focus X in view pixels.
     * @param y Focus Y in view pixels.
     */
    fun track(eventTimeMs: Long, x: Float, y: Float) = velocityEstimator.add(eventTimeMs, x, y)

    /**
     * Re-baseline the estimate at ([x], [y]) — used when a pan starts, and when a finger leaving
     * jumps the focus, so that discontinuity isn't read as a huge phantom flick.
     * @param eventTimeMs Event timestamp in milliseconds.
     * @param x Focus X in view pixels.
     * @param y Focus Y in view pixels.
     */
    fun rebaseline(eventTimeMs: Long, x: Float, y: Float) {
        velocityEstimator.reset()
        velocityEstimator.add(eventTimeMs, x, y)
    }

    /** Forget any latched release, so a fresh pan starts from no momentum. */
    fun clearRelease() {
        releaseVx = 0f
        releaseVy = 0f
    }

    /**
     * Read the pan's release velocity at ([x], [y]) and latch it as the fling seed, but only when the
     * release was a real flick (>= [Fling.MIN_SPEED_PX]). A slow drift, or the near-motionless
     * single-finger tail of a two-finger release, leaves the seed at zero, so only a genuine
     * one-finger flick coasts. The content glides opposite the finger, clamped to the platform's max
     * fling speed. The magnitude→coast response is shaped later by [Momentum.seed].
     * @param eventTimeMs Event timestamp in milliseconds.
     * @param x Release X in view pixels.
     * @param y Release Y in view pixels.
     */
    fun captureRelease(eventTimeMs: Long, x: Float, y: Float) {
        velocityEstimator.add(eventTimeMs, x, y)
        val v = velocityEstimator.velocity()
        if (hypot(v.vx, v.vy) < Fling.MIN_SPEED_PX) return
        releaseVx = (-v.vx).coerceIn(-maxFlingVelocity, maxFlingVelocity)
        releaseVy = (-v.vy).coerceIn(-maxFlingVelocity, maxFlingVelocity)
    }

    /**
     * Launch a decelerating glide from the latched release velocity, if it's fast enough. The seed
     * runs through [Momentum.seed]'s response curve so a slow flick barely coasts while a fast swipe
     * flies; [panSensitivity] then scales the glide to match the visual pan gain.
     * @param panSensitivity Scale factor for scroll gain (typically 1.0).
     */
    fun launch(panSensitivity: Float) {
        if (!canScroll()) return
        val (seedX, seedY) = Momentum.seed(releaseVx, releaseVy, strength, curve)
        fling.start(seedX * panSensitivity, seedY * panSensitivity)
        if (!fling.isMoving) return
        isFlinging = true
        lastFrameNanos = 0L
        cancelQueuedPaint()
        choreographer.postFrameCallback(frameCallback)
    }

    /** Halt any in-flight glide (a new touch, a cancel, or detachment). */
    fun stop() {
        if (isFlinging) {
            isFlinging = false
            choreographer.removeFrameCallback(frameCallback)
        }
        fling.stop()
    }

    /** One animation frame of the glide: decay velocity, scroll by the step, stop when done/stuck. */
    private fun onFrame(frameTimeNanos: Long) {
        if (!isFlinging) return
        // First frame seeds the clock; later frames use the real elapsed time so the glide is
        // frame-rate independent. Clamp big gaps (e.g. after a stall) so one step can't teleport.
        val dt = if (lastFrameNanos == 0L) 0f
        else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos
        val step = fling.advance(dt)
        val moved = scrollBy(step.dx, step.dy)
        paintFrame()
        // Stop once too slow, or when both axes are pinned at a bound (nowhere left to glide).
        val stuck = !moved && dt > 0f
        if (!fling.isMoving || stuck) stop() else choreographer.postFrameCallback(frameCallback)
    }
}
