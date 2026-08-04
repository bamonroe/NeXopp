package com.xopp.android.render

import com.xopp.android.format.model.Document

/**
 * The vertical-space tool's live drag: grab a line across a page and everything below it slides,
 * making or closing room (the pure page maths is [VerticalSpaceOps.shiftBelow]).
 *
 * It holds the whole gesture — the grabbed page, the grab line in page pt, its view-px Y for the
 * overlay, and the gesture-start document the shift is recomputed from each frame so the drag never
 * accumulates rounding drift. [DrawingSurfaceView] routes the touches in and draws the grab line
 * from [page] and [lineViewY]; nothing here touches a canvas.
 */
internal class VerticalSpaceDrag(
    /** The working document, read fresh on each frame (the view owns it). */
    private val document: () -> Document,
    /** Adopt [Document] as a live edit — the release records it as one undo step. */
    private val setDocument: (Document) -> Unit,
    /** The current page layout, for view-px ↔ page-pt conversion. */
    private val layout: () -> StackedLayout,
    /** The scroll offsets a touch is measured against. */
    private val viewport: ViewportState,
    /** Latch the pre-gesture document, so the whole drag records as one undo step on release. */
    private val beginGesture: (Document) -> Unit,
    /** Re-fit the layout and repaint after the shift changed the document. */
    private val refresh: () -> Unit,
) {

    /** True while a grab line is being dragged. */
    var active = false
        private set

    /** The page being reflowed. */
    var page = 0
        private set

    /** The grab line's view-px Y — where the overlay is drawn. */
    var lineViewY = 0f
        private set

    private var linePt = 0.0
    private var startDoc: Document? = null

    /**
     * Down with the vertical-space tool: latch the grabbed page and the page-local Y of the grab
     * line. Everything whose top edge is below that line follows the drag. Returns the grabbed page
     * index, or null when the touch missed every page (no drag starts).
     */
    fun begin(x: Float, y: Float): Int? {
        val box = layout().pageAt(x + viewport.scrollX, y + viewport.scrollY) ?: return null
        active = true
        page = box.index
        linePt = ptY(box, y)
        lineViewY = y
        val d = document()
        beginGesture(d)
        startDoc = d
        return box.index
    }

    /** Drag the grab line: re-apply the whole shift from the gesture-start snapshot. */
    fun move(y: Float) {
        val start = startDoc ?: return
        val box = layout().boxes.getOrNull(page) ?: return
        val dy = ptY(box, y) - linePt
        setDocument(document().copy(pages = VerticalSpaceOps.shiftBelow(start.pages, page, linePt, dy)))
        refresh()
    }

    /** Forget the live drag and its snapshot (a release, a cancel, or a fresh document). */
    fun reset() {
        active = false
        startDoc = null
    }

    private fun ptY(box: PageBox, vy: Float): Double = ((vy + viewport.scrollY - box.topPx) / box.scale).toDouble()
}
