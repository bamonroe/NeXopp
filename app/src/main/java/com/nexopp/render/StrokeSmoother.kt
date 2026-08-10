package com.nexopp.render

import com.nexopp.format.model.StrokePoint
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
 *
 * Samples arrive in view pixels, but the decimation radius must not be a *fixed* pixel distance:
 * a view pixel is a different amount of document at every magnification, so a fixed 1.6px radius
 * throws away 1.6/scale page points of real detail — over 3pt in a 4-column overview, where it
 * turns handwriting into visible corners and jumps. [StrokePrecision.stepPxFor] therefore takes
 * the *tighter* of a screen-space noise floor and a fixed document-space budget, so zooming out
 * can never cost stored precision. Call [reset] with that radius at the start of every stroke.
 */
class StrokeSmoother(
    private val positionAlpha: Float = POSITION_ALPHA,
    private val pressureAlpha: Float = PRESSURE_ALPHA,
    /** Decimation radius in view px; see [StrokePrecision.stepPxFor]. */
    var minStepPx: Float = MIN_STEP_PX,
) {
    /** One filtered sample, still in view pixels. */
    data class Sample(val x: Float, val y: Float, val pressure: Float)

    private var sx = 0f
    private var sy = 0f
    private var sp = 0f
    /** The last sample actually emitted — decimation measures against this, not the filter state. */
    private var ex = 0f
    private var ey = 0f
    private var ep = 0f
    private var started = false

    /**
     * Forget the previous stroke's state. Call once before the first sample of a new stroke,
     * passing the decimation radius for the magnification this stroke is being drawn at
     * ([StrokePrecision.stepPxFor]).
     */
    fun reset(stepPx: Float = minStepPx) {
        started = false
        minStepPx = stepPx
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
            ex = sx; ey = sy; ep = sp
            return Sample(sx, sy, sp)
        }
        val nx = sx + positionAlpha * (x - sx)
        val ny = sy + positionAlpha * (y - sy)
        val np = sp + pressureAlpha * (pressure - sp)
        sx = nx; sy = ny; sp = np
        // Measure against the last *emitted* point: a slow drift of many sub-threshold steps still
        // adds up to a real move, and comparing against the running filter state would swallow it.
        if (!force && hypot(nx - ex, ny - ey) < minStepPx && abs(np - ep) < MIN_PRESSURE_STEP) {
            // Below the noise floor in both channels: the filter has already advanced so the
            // smoothed path keeps tracking the pen, but don't emit a vertex for it.
            return null
        }
        ex = nx; ey = ny; ep = np
        return Sample(sx, sy, sp)
    }

    companion object {
        /** Fraction of the way each sample moves toward the raw position. Lower = smoother, laggier. */
        const val POSITION_ALPHA = 0.55f
        /** Pressure is noisier than position, so it is filtered harder. */
        const val PRESSURE_ALPHA = 0.3f
        /** Screen-space noise floor: samples closer than this (view px) are digitiser wobble. */
        const val MIN_STEP_PX = 1.6f

        /**
         * Document-space ceiling on what decimation may discard, in page points. Whatever the
         * magnification, a dropped sample never moved the pen more than this much *on the page*,
         * so a stroke drawn in a 4-column overview stores the same detail as one drawn at 800%.
         */
        const val MIN_STEP_PT = 0.8f
        /** …unless the pressure moved by at least this much, which is a visible width change. */
        const val MIN_PRESSURE_STEP = 0.02f
    }
}

/**
 * How much of the digitiser's detail a freehand stroke keeps.
 *
 * Both thinning stages — the live decimation in [StrokeSmoother] and the [StrokeSimplifier] pass
 * on the finished stroke — trade file size and redraw cost against fidelity, and the right trade
 * depends on the screen and the hand. A dense large-screen tablet resolves detail that a phone
 * would waste vertices on, and a fine hand-drawn sketch shows faceting a page of handwriting never
 * would. [factor] scales both budgets: below 1 keeps more vertices (smoother, bigger files), above
 * 1 keeps fewer.
 */
enum class StrokePrecision(val factor: Float, val label: String) {
    /** Fewest vertices: smallest files, visible faceting on tight curves. */
    ECONOMY(2f, "Economy"),

    /** The default trade — sub-pixel error at the magnification the stroke was drawn at. */
    BALANCED(1f, "Balanced"),

    /** Half the deviation budget: noticeably rounder curves on a high-density screen. */
    HIGH(0.5f, "High"),

    /** Near-raw input. Every sample the digitiser reports that moved at all is kept. */
    MAXIMUM(0.25f, "Maximum");

    /**
     * The live decimation radius, in view px, for a page drawn at [pxPerPt] view pixels per page
     * point ([PageBox.scale]: fit-to-width × user zoom — *not* the user zoom alone).
     *
     * The tighter of two budgets: a screen-space noise floor, which keeps a zoomed-in stroke from
     * recording every jitter sample, and a document-space ceiling, which keeps a zoomed-out stroke
     * from silently discarding page detail. Zooming out can only ever *increase* the sample rate.
     */
    fun stepPxFor(pxPerPt: Float): Float {
        if (pxPerPt <= 0f) return StrokeSmoother.MIN_STEP_PX * factor
        return minOf(StrokeSmoother.MIN_STEP_PX, StrokeSmoother.MIN_STEP_PT * pxPerPt) * factor
    }

    companion object {
        val DEFAULT = BALANCED
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
    /** Default deviation budget, in page points (1/72"): well under one pen width at 1 px/pt. */
    const val TOLERANCE_PT = 0.35

    /**
     * The on-screen deviation budget, in **view pixels**. Discarding less than this much detail is
     * invisible at the magnification the stroke was drawn at, whatever that magnification was.
     */
    const val TOLERANCE_PX = 0.35

    /**
     * Widest tolerance we'll ever use, however far the canvas is zoomed out. This is the
     * document-space half of the budget: below 1 px/pt the on-screen budget would let the pass
     * discard unbounded page detail, so the page-point figure takes over instead.
     */
    private const val MAX_TOLERANCE_PT = TOLERANCE_PT

    /**
     * The deviation budget to use for a stroke drawn at [pxPerPt] view pixels per page point,
     * tightened by [precision] (see [StrokePrecision.factor]).
     *
     * A fixed page-point tolerance is a *growing* on-screen error as you zoom in: at 8 px/pt a
     * 0.35pt budget is nearly 3 view pixels, enough to turn a curve into visible straight facets.
     * Expressing the budget in view pixels and dividing by the real px/pt keeps the discarded
     * detail sub-pixel at every magnification. Below 1 px/pt that division runs the other way and
     * would discard whole *points* of page detail, so [MAX_TOLERANCE_PT] caps it: zooming out
     * tightens the budget, it never loosens it.
     *
     * [pxPerPt] must be the **full** page→view factor ([PageBox.scale]: fit-to-width × user zoom),
     * not the user zoom alone. On a large tablet the fit-to-width factor is 2–4 px/pt on its own,
     * so using the zoom would leave the budget 2–4× too coarse at 100% and below — the facets the
     * precision setting exists to remove.
     */
    fun toleranceFor(pxPerPt: Float, precision: StrokePrecision = StrokePrecision.DEFAULT): Double {
        val budget = TOLERANCE_PX * precision.factor
        val scale = pxPerPt.toDouble()
        if (scale <= 0.0) return TOLERANCE_PT * precision.factor
        return (budget / scale).coerceAtMost(MAX_TOLERANCE_PT * precision.factor)
    }

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
