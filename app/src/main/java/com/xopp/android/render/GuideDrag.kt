package com.xopp.android.render

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * The on-canvas setsquare/compass overlay and the finger that manipulates it: where the guide is
 * posed, which part of it is held, and what a drag does to it.
 *
 * The guide is an input aid, never document content — it only pulls drawn vertices onto its edge
 * (see [project]). Its drag runs *alongside* the other gestures rather than replacing them, which
 * is the whole point: a finger holds the instrument steady while the pen rules along it, exactly
 * like the physical thing. That is why this owns its own pointer id.
 *
 * [DrawingSurfaceView] keeps the drawing of the overlay and the placement commands; the pose maths
 * itself is [DrawingGuide]'s.
 */
internal class GuideDrag(
    /** The current page layout, for view-px ↔ page-pt conversion. */
    private val layout: () -> StackedLayout,
    /** The scroll offsets a touch is measured against. */
    private val viewport: ViewportState,
    /** True when re-posing a setsquare snaps its angle to fixed steps. */
    private val snapRotation: () -> Boolean,
    /** Repaint the overlay after a drag frame. */
    private val render: () -> Unit,
    /** Notified whenever the user moves or re-poses the guide, so the pose can be remembered. */
    private val onGuideChanged: (DrawingGuide?) -> Unit,
) {

    /** The placed guide, or null when none is. */
    var pose: DrawingGuide? = null

    /** The page the [pose] is expressed in; the guide only constrains strokes on that page. */
    var page: Int = 0

    // Which part is held, and the grab offset from the guide's anchor so the body slides without
    // jumping under the finger.
    private var held = DrawingSurfaceView.GUIDE_DRAG_NONE
    private var pointerId = -1
    private var grabDx = 0.0
    private var grabDy = 0.0

    /** True while a finger is holding the guide. */
    val dragging: Boolean get() = held != DrawingSurfaceView.GUIDE_DRAG_NONE

    /**
     * ([x], [y]) page pt pulled onto the guide's nearest edge when one is placed on [boxIndex]'s
     * page and the point is within reach. Every drawn vertex — freehand and shape-tool alike — goes
     * through here, which is what makes the guide behave like a straightedge held against the page.
     */
    fun project(boxIndex: Int, x: Double, y: Double): Pair<Double, Double> {
        val g = pose ?: return x to y
        if (boxIndex != page) return x to y
        return g.project(x, y)
    }

    /**
     * Start dragging the guide if this finger landed on it: on the tip handle it re-poses the guide
     * (rotate + resize the setsquare, open the compass), anywhere else on its body it slides it.
     * Returns false when the touch missed, so the gesture falls through to the ordinary pan/draw.
     */
    fun begin(event: MotionEvent, pointerIndex: Int): Boolean {
        val g = pose ?: return false
        val box = layout().boxes.getOrNull(page) ?: return false
        val px = box.toPtX(event.getX(pointerIndex), viewport.scrollX)
        val py = box.toPtY(event.getY(pointerIndex), viewport.scrollY)
        val handleReach = (DrawingSurfaceView.HANDLE_HIT_PX / box.scale.coerceAtLeast(0.01f)).toDouble()
        val tip = tipOf(g)
        if (hypot(px - tip.first, py - tip.second) <= handleReach) {
            held = DrawingSurfaceView.GUIDE_DRAG_TIP
        } else if (bodyHit(g, px, py, handleReach)) {
            held = DrawingSurfaceView.GUIDE_DRAG_BODY
            grabDx = px - g.x
            grabDy = py - g.y
        } else {
            return false
        }
        pointerId = event.getPointerId(pointerIndex)
        return true
    }

    fun move(event: MotionEvent) {
        val g = pose ?: return
        val box = layout().boxes.getOrNull(page) ?: return
        val pointerIndex = event.findPointerIndex(pointerId)
        if (pointerIndex < 0) return
        val px = box.toPtX(event.getX(pointerIndex), viewport.scrollX)
        val py = box.toPtY(event.getY(pointerIndex), viewport.scrollY)
        pose = when {
            held == DrawingSurfaceView.GUIDE_DRAG_BODY -> g.moved(px - grabDx - g.x, py - grabDy - g.y)
            g is DrawingGuide.Setsquare -> g.aimedAt(px, py, snapRotation())
            g is DrawingGuide.Compass -> g.openedTo(px, py)
            else -> g
        }
        render()
    }

    /** Release the guide when the finger holding it lifts; other pointers lifting leave it held. */
    fun end(event: MotionEvent?) {
        if (!dragging) return
        if (event != null && event.getPointerId(event.actionIndex) != pointerId) return
        held = DrawingSurfaceView.GUIDE_DRAG_NONE
        pointerId = -1
        onGuideChanged(pose)
    }

    /** The re-pose handle: the setsquare's long-leg tip, or the compass's pencil point. */
    fun tipOf(g: DrawingGuide): Pair<Double, Double> = when (g) {
        is DrawingGuide.Setsquare -> g.corners()[1]
        is DrawingGuide.Compass -> (g.x + g.radius) to g.y
    }

    /**
     * True when ([px], [py]) is on the guide's grabbable body: the setsquare's *interior* or the
     * compass's hub. Deliberately **not** the drawing edge — the edge belongs to the pen, so that a
     * finger drawn along it still draws instead of dragging the instrument out from under itself.
     */
    private fun bodyHit(g: DrawingGuide, px: Double, py: Double, hubReach: Double): Boolean =
        when (g) {
            is DrawingGuide.Setsquare -> g.contains(px, py)
            is DrawingGuide.Compass -> hypot(px - g.x, py - g.y) <= hubReach
        }

}
