package com.nexopp.render

import android.view.MotionEvent
import com.nexopp.format.model.Document
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A live selection: the page it lives on and the position-addressed elements it holds. */
internal data class ActiveSelection(val pageIndex: Int, val refs: Set<ElementRef>)

/**
 * Every gesture that creates or transforms a selection: the rubber-band/lasso that picks elements,
 * and the move / uniform-resize / rotate drags of what is picked.
 *
 * This is the one owner of [selection] and of the transform's gesture-start snapshot — a live
 * resize or rotate recomputes from that snapshot each frame, so a drag never accumulates rounding
 * drift. [DrawingSurfaceView] keeps only the routing (which handler a MOVE goes to) and the drawing
 * of the marquee/outline, and reads the state it paints through this class.
 *
 * It touches the canvas only through the callbacks it is handed, so the whole select-and-transform
 * path is testable without a `SurfaceView`. The pure page maths stays in [SelectionOps] and
 * [SelectionTester]; this class turns touches into calls on them.
 */
internal class SelectionGestureController(
    /** The working document, read fresh at the start of every gesture (the view owns it). */
    private val document: () -> Document,
    /** Adopt [Document] as a live edit — assigned straight through, not recorded; see [beginGesture]. */
    private val setDocument: (Document) -> Unit,
    /** The current page layout, for view-px ↔ page-pt conversion and page hit-testing. */
    private val layout: () -> StackedLayout,
    /** The scroll offsets a touch is measured against. */
    private val viewport: ViewportState,
    /** True when the marquee traces a free-form lasso instead of a rectangle. */
    private val lassoMode: () -> Boolean,
    /** True when a rotate snaps to fixed angle steps. */
    private val snapRotation: () -> Boolean,
    /** Latch the pre-gesture document, so the whole drag records as one undo step on release. */
    private val beginGesture: (Document) -> Unit,
    /** Re-fit the layout and repaint after a live transform changed the document. */
    private val refresh: () -> Unit,
    /** Repaint without re-laying out (the marquee overlay). */
    private val render: () -> Unit,
    /** Notified whenever the selection appears or clears, so the chrome can show contextual actions. */
    private val onSelectionChanged: (Boolean) -> Unit,
) {

    /** The current selection (a page index + the refs of its selected elements), or null. */
    var selection: ActiveSelection? = null

    // Live rubber-band marquee (view px) and the page it selects within.
    var banding = false
        private set
    private var bandPage = 0
    var bandX0 = 0f
        private set
    var bandY0 = 0f
        private set
    var bandX1 = 0f
        private set
    var bandY1 = 0f
        private set

    /** Free-form lasso path (view px, x/y interleaved) captured while banding in lasso mode. */
    val lassoPts = ArrayList<Float>()

    // Live move of the current selection (also the base snapshot for resize/rotate, so a live
    // transform recomputes from the gesture-start document each frame and never drifts).
    var moving = false
        private set
    private var startDoc: Document? = null
    private var moveStartPtX = 0.0
    private var moveStartPtY = 0.0

    // Live uniform resize about a fixed anchor (the opposite corner), in page-local pt.
    var resizing = false
        private set
    private var resizeAnchorX = 0.0
    private var resizeAnchorY = 0.0
    private var resizeStartDist = 1.0

    // Live rotate about the selection centre (pt); strokes only.
    var rotating = false
        private set
    private var rotatePivotX = 0.0
    private var rotatePivotY = 0.0
    private var rotateStartAngle = 0.0

    /** True while any of the three transforms is mid-drag. */
    val transforming: Boolean get() = moving || resizing || rotating

    /** Drop the selection without recording anything (a view-only change). */
    fun clearSelection() {
        if (selection == null) return
        selection = null
        onSelectionChanged(false)
    }

    /** Forget every live gesture and the snapshot behind it (a cancel, or a fresh document). */
    fun reset() {
        moving = false
        resizing = false
        rotating = false
        banding = false
        startDoc = null
    }

    /** Release the gesture-start snapshot once the edit has been recorded. */
    fun releaseSnapshot() {
        startDoc = null
    }

    /**
     * Down in Select mode. With a live selection, a touch near a corner **resizes**, near the
     * right-edge rotate knob (strokes only) **rotates**, and inside the outline **moves**; otherwise it
     * starts a new rubber-band (or lasso). The handle hit-tests run in view px against the drawn outline.
     */
    fun beginSelect(event: MotionEvent) {
        val sel = selection
        if (sel != null && dispatchHandle(event, sel)) return
        beginBand(event)
    }

    /** Try to start a resize/rotate/move for [sel] from a down at (event.x, event.y); else false. */
    private fun dispatchHandle(event: MotionEvent, sel: ActiveSelection): Boolean {
        val box = layout().boxes.getOrNull(sel.pageIndex) ?: return false
        val page = document().pages.getOrNull(sel.pageIndex) ?: return false
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return false
        // Rotate knob poking out midway from the right edge, offered only when every element is a stroke.
        if (isAllStrokes(sel)) {
            val midY = ((b.top + b.bottom) / 2 * box.scale + box.topPx - viewport.scrollY).toFloat()
            val rightX = (b.right * box.scale + box.leftPx - viewport.scrollX).toFloat() +
                DrawingSurfaceDefaults.SELECT_PAD_PX + DrawingSurfaceDefaults.ROTATE_ARM_PX
            if (hypot(event.x - rightX, event.y - midY) <= DrawingSurfaceDefaults.HANDLE_HIT_PX) {
                beginRotate(event, box, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
                return true
            }
        }
        // Four corner resize handles; the anchor is the diagonally-opposite corner.
        val pad = DrawingSurfaceDefaults.SELECT_PAD_PX
        val cornersPt = arrayOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
        for (i in 0..3) {
            val padX = if (cornersPt[i].first == b.left) -pad else pad
            val padY = if (cornersPt[i].second == b.top) -pad else pad
            val hx = (cornersPt[i].first * box.scale + box.leftPx - viewport.scrollX).toFloat() + padX
            val hy = (cornersPt[i].second * box.scale + box.topPx - viewport.scrollY).toFloat() + padY
            if (hypot(event.x - hx, event.y - hy) <= DrawingSurfaceDefaults.HANDLE_HIT_PX) {
                val anchor = cornersPt[(i + 2) % 4]
                beginResize(event, box, anchor.first, anchor.second)
                return true
            }
        }
        // Inside the outline: move.
        if (b.expand(DrawingSurfaceDefaults.MOVE_GRAB_PAD).contains(box.toPtX(event.x, viewport.scrollX), box.toPtY(event.y, viewport.scrollY))) {
            beginMove(event, box)
            return true
        }
        return false
    }

    /** True if every element in [sel] is a stroke (the only element whose rotation round-trips) —
     *  the condition for offering the rotate knob, which the view also asks before drawing it. */
    fun isAllStrokes(sel: ActiveSelection): Boolean {
        val page = document().pages.getOrNull(sel.pageIndex) ?: return false
        return sel.refs.isNotEmpty() && sel.refs.all {
            page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) is Stroke
        }
    }

    private fun beginMove(event: MotionEvent, box: PageBox) {
        moving = true
        snapshot()
        moveStartPtX = box.toPtX(event.x, viewport.scrollX)
        moveStartPtY = box.toPtY(event.y, viewport.scrollY)
    }

    private fun beginResize(event: MotionEvent, box: PageBox, anchorX: Double, anchorY: Double) {
        resizing = true
        snapshot()
        resizeAnchorX = anchorX
        resizeAnchorY = anchorY
        resizeStartDist = hypot(box.toPtX(event.x, viewport.scrollX) - anchorX, box.toPtY(event.y, viewport.scrollY) - anchorY).coerceAtLeast(1e-3)
    }

    private fun beginRotate(event: MotionEvent, box: PageBox, pivotX: Double, pivotY: Double) {
        rotating = true
        snapshot()
        rotatePivotX = pivotX
        rotatePivotY = pivotY
        rotateStartAngle = atan2(box.toPtY(event.y, viewport.scrollY) - pivotY, box.toPtX(event.x, viewport.scrollX) - pivotX)
    }

    /** Latch the document a transform starts from, both as the undo point and as the live base. */
    private fun snapshot() {
        val d = document()
        beginGesture(d)
        startDoc = d
    }

    /** Drag the selection: translate the elements from their gesture-start positions, live. */
    fun moveSelect(event: MotionEvent) = transform { sel, start, box ->
        SelectionOps.translate(
            start.pages, sel.pageIndex, sel.refs,
            box.toPtX(event.x, viewport.scrollX) - moveStartPtX, box.toPtY(event.y, viewport.scrollY) - moveStartPtY,
        )
    }

    /** Resize the selection: a uniform scale by (current distance / start distance) about the anchor. */
    fun resizeSelect(event: MotionEvent) = transform { sel, start, box ->
        val dist = hypot(box.toPtX(event.x, viewport.scrollX) - resizeAnchorX, box.toPtY(event.y, viewport.scrollY) - resizeAnchorY)
        val factor = (dist / resizeStartDist)
            .coerceIn(DrawingSurfaceDefaults.MIN_RESIZE, DrawingSurfaceDefaults.MAX_RESIZE)
        SelectionOps.scale(start.pages, sel.pageIndex, sel.refs, factor, resizeAnchorX, resizeAnchorY)
    }

    /** Rotate the selection's strokes by the angle swept around the pivot since the gesture start. */
    fun rotateSelect(event: MotionEvent) = transform { sel, start, box ->
        val swept = atan2(box.toPtY(event.y, viewport.scrollY) - rotatePivotY, box.toPtX(event.x, viewport.scrollX) - rotatePivotX) - rotateStartAngle
        // Snapping the swept angle (not the absolute one) keeps the steps relative to where the
        // selection started, so a snapped drag returns exactly to the original orientation at rest.
        val angle = if (snapRotation()) Snapping.snapAngle(swept) else swept
        SelectionOps.rotate(start.pages, sel.pageIndex, sel.refs, angle, rotatePivotX, rotatePivotY)
    }

    /** The shared body of the three live transforms: recompute from the snapshot, then repaint. */
    private inline fun transform(pages: (ActiveSelection, Document, PageBox) -> List<Page>) {
        val sel = selection ?: return
        val start = startDoc ?: return
        val box = layout().boxes.getOrNull(sel.pageIndex) ?: return
        setDocument(document().copy(pages = pages(sel, start, box)))
        refresh()
    }

    /**
     * Finish a move: if the selection's centre now sits over a different page, re-home the elements
     * onto that page's top layer (mapping through both pages' pt frames so they land where they're
     * dropped). The whole move records as one undoable edit by the caller's gesture bookkeeping.
     */
    fun commitMove() {
        val sel = selection ?: return
        val srcBox = layout().boxes.getOrNull(sel.pageIndex) ?: return
        val page = document().pages.getOrNull(sel.pageIndex) ?: return
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return
        val centreXContent = ((b.left + b.right) / 2 * srcBox.scale + srcBox.leftPx).toFloat()
        val centreYContent = ((b.top + b.bottom) / 2 * srcBox.scale + srcBox.topPx).toFloat()
        val target = layout().nearestPage(centreXContent, centreYContent) ?: return
        if (target.index == sel.pageIndex) return
        val s = srcBox.scale.toDouble() / target.scale.toDouble()
        val dx = (srcBox.leftPx - target.leftPx) / target.scale.toDouble()
        val dy = (srcBox.topPx - target.topPx) / target.scale.toDouble()
        val (pages, refs) = SelectionOps.moveToPage(document().pages, sel.pageIndex, target.index, sel.refs, s, dx, dy)
        setDocument(document().copy(pages = pages))
        selection = ActiveSelection(target.index, refs)
    }

    fun beginBand(event: MotionEvent) {
        clearSelection()
        banding = true
        bandPage = layout().pageAt(event.x + viewport.scrollX, event.y + viewport.scrollY)?.index ?: 0
        bandX0 = event.x; bandY0 = event.y
        bandX1 = event.x; bandY1 = event.y
        lassoPts.clear()
        if (lassoMode()) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    fun bandMove(event: MotionEvent) {
        bandX1 = event.x
        bandY1 = event.y
        if (lassoMode()) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    /**
     * Up from a marquee: a tap (no real drag) picks the topmost element; a rectangle drag selects
     * everything wholly enclosed; a lasso drag selects everything wholly inside the traced polygon.
     */
    fun commitBand() {
        val box = layout().boxes.getOrNull(bandPage) ?: return
        val page = document().pages.getOrNull(bandPage) ?: return
        val isTap = hypot(bandX1 - bandX0, bandY1 - bandY0) <= DrawingSurfaceDefaults.TAP_SLOP_PX
        val refs: Set<ElementRef> = when {
            isTap -> SelectionTester.pickTopmost(page, box.toPtX(bandX0, viewport.scrollX), box.toPtY(bandY0, viewport.scrollY))?.let { setOf(it) } ?: emptySet()
            lassoMode() -> {
                val poly = ArrayList<Vec2>(lassoPts.size / 2)
                var i = 0
                while (i < lassoPts.size) { poly.add(Vec2(box.toPtX(lassoPts[i], viewport.scrollX), box.toPtY(lassoPts[i + 1], viewport.scrollY))); i += 2 }
                SelectionTester.inPolygon(page, poly)
            }
            else -> {
                val rect = Bounds(
                    min(box.toPtX(bandX0, viewport.scrollX), box.toPtX(bandX1, viewport.scrollX)), min(box.toPtY(bandY0, viewport.scrollY), box.toPtY(bandY1, viewport.scrollY)),
                    max(box.toPtX(bandX0, viewport.scrollX), box.toPtX(bandX1, viewport.scrollX)), max(box.toPtY(bandY0, viewport.scrollY), box.toPtY(bandY1, viewport.scrollY)),
                )
                SelectionTester.inRect(page, rect)
            }
        }
        selection = if (refs.isEmpty()) null else ActiveSelection(bandPage, refs)
        onSelectionChanged(selection != null)
        render()
    }

    /** View px -> page-local pt for [box]. */
}
