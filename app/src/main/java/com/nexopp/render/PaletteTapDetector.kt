package com.nexopp.render

import kotlin.math.hypot

/**
 * The pure state machine behind the [PaletteInvocation.TWO_FINGER_TAP] gesture: two fingers that go
 * down together, stay put, and come up quickly open the radial palette midway between them.
 *
 * It is deliberately free of Android types so every disqualification rule is unit-testable on the
 * JVM (`PaletteTapDetectorTest`); `DrawingSurfaceView` only feeds it the raw pointer positions it
 * already has. The rules are what keep it from stealing the existing gestures:
 *
 *  * a third pointer, or a second finger arriving late, cancels it outright;
 *  * travel past [slopPx] on either finger makes it a pan or a pinch, not a tap;
 *  * a hold longer than [timeoutMs] is a deliberate two-finger drag, not a tap.
 *
 * Because it only ever *reports* a tap on release, the pan/zoom underneath runs as usual until the
 * moment the tap is confirmed — a rejected tap costs the user nothing.
 */
class PaletteTapDetector(private val slopPx: Float, private val timeoutMs: Long) {

    private var armed = false
    private var downTime = 0L
    private var startA = 0f to 0f
    private var startB = 0f to 0f
    private var lastA = 0f to 0f
    private var lastB = 0f to 0f

    /** Both fingers landed: begin tracking a candidate tap at [time] (event-time millis). */
    fun start(time: Long, ax: Float, ay: Float, bx: Float, by: Float) {
        armed = true
        downTime = time
        startA = ax to ay
        startB = bx to by
        lastA = startA
        lastB = startB
    }

    /** Track both fingers; travel past the slop disqualifies the candidate (it's a pan or pinch). */
    fun move(ax: Float, ay: Float, bx: Float, by: Float) {
        if (!armed) return
        lastA = ax to ay
        lastB = bx to by
        if (travelled(startA, lastA) > slopPx || travelled(startB, lastB) > slopPx) armed = false
    }

    /** Give up on the candidate — a third finger, a lifted stylus, a cancelled gesture. */
    fun cancel() {
        armed = false
    }

    /**
     * A finger lifted at [time]: the midpoint to open the palette at when this really was a tap, or
     * `null` when it wasn't. Either way the candidate is consumed, so one gesture fires at most once.
     */
    fun release(time: Long): Pair<Float, Float>? {
        if (!armed) return null
        armed = false
        if (time - downTime > timeoutMs) return null
        return (lastA.first + lastB.first) / 2f to (lastA.second + lastB.second) / 2f
    }

    private fun travelled(from: Pair<Float, Float>, to: Pair<Float, Float>): Float =
        hypot(to.first - from.first, to.second - from.second)
}
