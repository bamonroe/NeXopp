package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Streaming jitter filter for freehand input.
 *
 * Digitisers report a noisy signal: the reported position wobbles by a pixel or two around the
 * real pen path, and the reported pressure jumps sample-to-sample even when the hand is steady.
 * Fed straight into [StrokePainter] that shows up as a visibly ragged, lumpy line, where desktop
 * Xournal++ draws a smooth one. This filter sits between the [android.view.MotionEvent] samples
 * and the page-space points, and does three things, in order, per sample:
 *
 *  1. **Exponential position smoothing** — pulls each sample a fraction of the way from the last
 *     smoothed point toward the raw one, which removes high-frequency wobble while adding only a
 *     sub-sample of lag.
 *  2. **Exponential pressure smoothing** — the same filter on the pressure channel, more strongly
 *     (pressure is the noisier of the two), so the stroke tapers instead of pulsing.
 *  3. **Decimation** — drops a sample that landed almost on top of the previous kept one. Those
 *     points carry no shape information but cost a vertex in the file and a segment per redraw.
 *
 * State is per stroke: construct one in `startStroke` (or call [reset]) and feed every sample.
 * Working in **view pixels** keeps the thresholds zoom-independent — the same physical wobble is
 * filtered the same way whether the page is at 50% or 800%.
 */
class StrokeSmoother(
    private val positionAlpha: Float = POSITION_ALPHA,
    private val pressureAlpha: Float = PRESSURE_ALPHA,
    private val minStepPx: Float = MIN_STEP_PX,
) {
    /** One filtered sample, still in view pixels. */
    data class Sample(val x: Float, val y: Float, val pressure: Float)

    private var sx = 0f
    private var sy = 0f
    private var sp = 0f
    private var started = false

    /** Forget the previous stroke's state. Call once before the first sample of a new stroke. */
    fun reset() {
        started = false
    }

    /**
     * Filter one raw sample. Returns the smoothed sample to append, or `null` when the sample was
     * decimated away. [force] keeps the point regardless of distance — pass it for the final
     * sample of a stroke so the line ends exactly where the pen lifted.
     */
    fun accept(x: Float, y: Float, pressure: Float, force: Boolean = false): Sample? {
        if (!started) {
            started = true
            sx = x; sy = y; sp = pressure
            return Sample(sx, sy, sp)
        }
        val nx = sx + positionAlpha * (x - sx)
        val ny = sy + positionAlpha * (y - sy)
        val np = sp + pressureAlpha * (pressure - sp)
        if (!force && hypot(nx - sx, ny - sy) < minStepPx && abs(np - sp) < MIN_PRESSURE_STEP) {
            // Below the noise floor in both channels: still advance the filter so the smoothed
            // path keeps tracking the pen, but don't emit a vertex for it.
            sx = nx; sy = ny; sp = np
            return null
        }
        sx = nx; sy = ny; sp = np
        return Sample(sx, sy, sp)
    }

    companion object {
        /** Fraction of the way each sample moves toward the raw position. Lower = smoother, laggier. */
        const val POSITION_ALPHA = 0.55f
        /** Pressure is noisier than position, so it is filtered harder. */
        const val PRESSURE_ALPHA = 0.3f
        /** Samples closer together than this (view px) carry no shape information. */
        const val MIN_STEP_PX = 1.6f
        /** …unless the pressure moved by at least this much, which is a visible width change. */
        const val MIN_PRESSURE_STEP = 0.02f
    }
}

/**
 * Ramer–Douglas–Peucker simplification of a finished stroke.
 *
 * The live filter above only ever sees two samples at a time, so it can't tell a long straight
 * run from a tight curve. Once the pen lifts we can: this pass drops any vertex that lies within
 * [tolerance] page points of the chord between its neighbours, which collapses straight segments
 * to their endpoints and leaves curvature untouched. Fewer vertices means a smaller `.xopp` and
 * fewer `drawLine` calls per redraw, with no visible change to the stroke.
 *
 * Widths ride along with the points that survive; endpoints are always kept.
 */
object StrokeSimplifier {
    /** Default deviation budget, in page points (1/72"): well under one pen width at 100% zoom. */
    const val TOLERANCE_PT = 0.35

    /** Widest tolerance we'll ever use, however far the canvas is zoomed out. */
    private const val MIN_ZOOM_DIVISOR = 0.5

    /**
     * The deviation budget to use while drawing at [zoom].
     *
     * A fixed page-point tolerance is a *growing* on-screen error as you zoom in: at 800% the
     * 0.35pt budget is nearly 3 view pixels, enough to turn a curve into visible straight
     * facets. Scaling the budget by the zoom keeps the discarded detail sub-pixel on screen at
     * every magnification, so a stroke drawn zoomed-in stays as smooth as it looked. Zooming out
     * is clamped so we never thin a stroke so aggressively that it looks wrong once zoomed back in.
     */
    fun toleranceFor(zoom: Float): Double = TOLERANCE_PT / zoom.toDouble().coerceAtLeast(MIN_ZOOM_DIVISOR)

    fun simplify(points: List<StrokePoint>, tolerance: Double = TOLERANCE_PT): List<StrokePoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        reduce(points, 0, points.size - 1, tolerance, keep)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Mark the farthest point in `first..last` if it exceeds [tolerance], then recurse either side. */
    private fun reduce(pts: List<StrokePoint>, first: Int, last: Int, tolerance: Double, keep: BooleanArray) {
        if (last <= first + 1) return
        var bestIndex = -1
        var bestDist = tolerance
        for (i in first + 1 until last) {
            val d = perpendicularDistance(pts[i], pts[first], pts[last])
            if (d > bestDist) {
                bestDist = d
                bestIndex = i
            }
        }
        if (bestIndex < 0) return
        keep[bestIndex] = true
        reduce(pts, first, bestIndex, tolerance, keep)
        reduce(pts, bestIndex, last, tolerance, keep)
    }

    /** Distance from [p] to the segment [a]–[b] (to the point itself when the segment is degenerate). */
    private fun perpendicularDistance(p: StrokePoint, a: StrokePoint, b: StrokePoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-9) return hypot(p.x - a.x, p.y - a.y)
        return abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / len
    }
}
