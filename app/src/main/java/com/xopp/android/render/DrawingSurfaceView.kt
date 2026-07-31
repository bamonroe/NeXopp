package com.xopp.android.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool

/**
 * The low-latency stylus canvas. Holds the whole [Document] and renders every page top-to-bottom,
 * each with its background ruling and all its layers, scaled to fit the view width (see
 * [PageStacker] / [BackgroundRenderer]). One finger draws; two fingers scroll the page stack.
 * Strokes are drawn here; text boxes, images, and LaTeX images are drawn by [ElementRenderer].
 * Pressure is captured at the fidelity `.xopp` stores — this is where round-trip safety starts
 * (see `docs/architecture.md`).
 */
class DrawingSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** The working document; edits append strokes to it and [toDocument] returns it verbatim. */
    private var doc: Document = Document(pages = listOf(blankPage()))
    private var layout: StackedLayout = StackedLayout(emptyList(), 0f, 0f)
    private var scrollY = 0f
    private var scrollX = 0f
    private var zoom = 1f

    /** In-progress stroke (page-local pt space) and the page it belongs to. */
    private var current: ArrayList<StrokePoint>? = null
    private var currentPage = 0
    private var scrolling = false
    private var erasing = false
    private var lastFocusY = 0f
    private var lastFocusX = 0f

    /** Undo/redo snapshots of the whole [Document] (cheap: immutable pages/layers share structure). */
    private val history = EditHistory<Document>()
    /** The document as it was when the current gesture began, so one gesture is one undo step. */
    private var gestureStartDoc: Document? = null

    /** Notified with (canUndo, canRedo) whenever the history changes, so the chrome can enable buttons. */
    var onHistoryChanged: ((Boolean, Boolean) -> Unit)? = null
    /** Notified with the current zoom factor whenever it changes, so the chrome can show the level. */
    var onZoomChanged: ((Float) -> Unit)? = null
    /** Notified with the page count whenever it changes (load, add, remove). */
    var onPageCountChanged: ((Int) -> Unit)? = null

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f
    /** When true, one finger pans the canvas (the Hand tool) instead of drawing/erasing. */
    var handMode: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val elementRenderer = ElementRenderer()

    init {
        holder.addCallback(this)
    }

    /** Replace the canvas contents with [doc] (all pages, layers, and unmodelled elements). */
    fun load(doc: Document) {
        this.doc = if (doc.pages.isEmpty()) doc.copy(pages = listOf(blankPage())) else doc
        scrollY = 0f
        scrollX = 0f
        history.clear()
        notifyHistory()
        onPageCountChanged?.invoke(this.doc.pages.size)
        relayout()
        render()
    }

    /** The current working document — every page, layer, and preserved element, ready to save. */
    fun toDocument(): Document = doc

    // --- undo / redo ---------------------------------------------------------------------------

    /** Revert the most recent edit (stroke or erase gesture). No-op when there's nothing to undo. */
    fun undo() {
        doc = history.undo(doc) ?: return
        afterHistoryMove()
    }

    /** Re-apply the most recently undone edit. No-op when there's nothing to redo. */
    fun redo() {
        doc = history.redo(doc) ?: return
        afterHistoryMove()
    }

    private fun afterHistoryMove() {
        current = null
        relayout()
        render()
        notifyHistory()
    }

    private fun notifyHistory() {
        onHistoryChanged?.invoke(history.canUndo, history.canRedo)
    }

    // --- zoom ----------------------------------------------------------------------------------

    fun zoomIn() = setZoom(zoom * ZOOM_STEP)
    fun zoomOut() = setZoom(zoom / ZOOM_STEP)
    fun resetZoom() = setZoom(1f)

    /** Set the zoom factor (clamped), keeping the point at the viewport centre roughly fixed. */
    private fun setZoom(target: Float) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val yFrac = if (layout.totalHeightPx > 0f) (scrollY + height / 2f) / layout.totalHeightPx else 0f
        val xFrac = if (layout.contentWidthPx > 0f) (scrollX + width / 2f) / layout.contentWidthPx else 0f
        zoom = next
        relayout()
        scrollY = (yFrac * layout.totalHeightPx - height / 2f).coerceIn(0f, maxScrollY())
        scrollX = (xFrac * layout.contentWidthPx - width / 2f).coerceIn(0f, maxScrollX())
        render()
        onZoomChanged?.invoke(zoom)
    }

    // --- pages ---------------------------------------------------------------------------------

    /** Insert a blank page (same size/background as the current one) after the page in view. */
    fun addPage() {
        val at = currentPageIndex()
        editPages(PageOps.addAfter(doc.pages, at))
    }

    /** Delete the page currently in view. No-op when only one page remains. */
    fun removePage() {
        val at = currentPageIndex()
        editPages(PageOps.removeAt(doc.pages, at))
    }

    /** Apply a new page list as one undoable edit, if it actually differs. */
    private fun editPages(pages: List<Page>) {
        if (pages === doc.pages) return
        val before = doc
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        onPageCountChanged?.invoke(pages.size)
        // keep the viewport valid, then keep zoom's centre-fraction sane
        relayout()
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        render()
    }

    /** Index of the page nearest the viewport centre — the one add/remove act on. */
    private fun currentPageIndex(): Int =
        layout.pageAt(scrollY + height / 2f)?.index ?: doc.pages.lastIndex.coerceAtLeast(0)

    // --- touch: one finger draws (or erases), two fingers scroll -------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> when {
                handMode -> beginScroll(event)
                tool == Tool.ERASER -> startErase(event)
                else -> startStroke(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> beginScroll(event)
            MotionEvent.ACTION_MOVE -> when {
                scrolling -> doScroll(event)
                erasing -> eraseMove(event)
                else -> extendStroke(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastFocusY = focusY(event, skip = event.actionIndex)
                lastFocusX = focusX(event, skip = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> endGesture()
            MotionEvent.ACTION_CANCEL -> { current = null; scrolling = false; erasing = false; gestureStartDoc = null }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun startStroke(event: MotionEvent) {
        scrolling = false
        gestureStartDoc = doc
        val box = layout.pageAt(event.y + scrollY) ?: run { current = null; return }
        currentPage = box.index
        current = ArrayList<StrokePoint>().also { addSamples(event, box, it) }
    }

    private fun extendStroke(event: MotionEvent) {
        val box = layout.boxes.getOrNull(currentPage) ?: return
        current?.let { addSamples(event, box, it); render() }
    }

    /** The eraser: touch/drag deletes any stroke it passes over on the page under the finger. */
    private fun startErase(event: MotionEvent) {
        scrolling = false
        erasing = true
        gestureStartDoc = doc
        val box = layout.pageAt(event.y + scrollY) ?: return
        currentPage = box.index
        eraseAt(box, event.x, event.y)
    }

    private fun eraseMove(event: MotionEvent) {
        val box = layout.boxes.getOrNull(currentPage) ?: return
        eraseAt(box, event.x, event.y)
    }

    private fun eraseAt(box: PageBox, vx: Float, vy: Float) {
        val px = ((vx + scrollX - box.leftPx) / box.scale).toDouble()
        val py = ((vy + scrollY - box.topPx) / box.scale).toDouble()
        val radius = ERASER_RADIUS_PX / box.scale
        if (eraseStrokes(currentPage, px, py, radius.toDouble())) render()
    }

    /** A second finger (or the Hand tool) started panning: abandon any partial stroke/erase. */
    private fun beginScroll(event: MotionEvent) {
        current = null
        erasing = false
        scrolling = true
        lastFocusY = focusY(event, skip = -1)
        lastFocusX = focusX(event, skip = -1)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = focusY(event, skip = -1)
        val fx = focusX(event, skip = -1)
        scrollY = (scrollY + (lastFocusY - fy)).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + (lastFocusX - fx)).coerceIn(0f, maxScrollX())
        lastFocusY = fy
        lastFocusX = fx
        render()
    }

    private fun maxScrollY(): Float = (layout.totalHeightPx - height).coerceAtLeast(0f)
    private fun maxScrollX(): Float = (layout.contentWidthPx - width).coerceAtLeast(0f)

    private fun endGesture() {
        if (!scrolling && !erasing) commitCurrent()
        scrolling = false
        erasing = false
        finishGesture()
    }

    /** Record one undo step if this gesture actually changed the document. */
    private fun finishGesture() {
        val start = gestureStartDoc ?: return
        gestureStartDoc = null
        if (doc !== start) {
            history.record(start)
            notifyHistory()
        }
    }

    /** Mean Y of all pointers except [skip] (an index being lifted), in view px. */
    private fun focusY(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getY(i); n++ }
        return if (n == 0) event.y else sum / n
    }

    /** Mean X of all pointers except [skip] (an index being lifted), in view px. */
    private fun focusX(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getX(i); n++ }
        return if (n == 0) event.x else sum / n
    }

    /** Append every sample in this event — historical first — as pressure-scaled page-local points. */
    private fun addSamples(event: MotionEvent, box: PageBox, into: MutableList<StrokePoint>) {
        for (h in 0 until event.historySize) {
            into += point(box, event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h))
        }
        into += point(box, event.x, event.y, event.pressure)
    }

    private fun point(box: PageBox, vx: Float, vy: Float, pressure: Float): StrokePoint {
        val p = if (pressure <= 0f) 1f else pressure
        return StrokePoint(
            x = ((vx + scrollX - box.leftPx) / box.scale).toDouble(),
            y = ((vy + scrollY - box.topPx) / box.scale).toDouble(),
            width = (baseWidthPt * (0.4f + 0.6f * p)).toDouble(),
        )
    }

    private fun commitCurrent() {
        val pts = current ?: return
        current = null
        if (pts.size >= 2) appendStroke(currentPage, Stroke(tool, strokeColor(), "round", pts, uniformWidth = false))
        render()
    }

    /** Highlighter is stored semi-transparent so it round-trips (and renders) translucent. */
    private fun strokeColor(): Int =
        if (tool == Tool.HIGHLIGHTER && (colorArgb ushr 24) == 0xFF) {
            (colorArgb and 0x00FFFFFF) or 0x80000000.toInt()
        } else {
            colorArgb
        }

    /** Append [stroke] to the top (last) layer of page [pageIndex], rebuilding the model. */
    private fun appendStroke(pageIndex: Int, stroke: Stroke) {
        val pages = doc.pages.toMutableList()
        val page = pages[pageIndex]
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val top = layers.lastIndex
        layers[top] = Layer(layers[top].elements + stroke)
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        relayout() // rebuild boxes so they reference the updated pages, not stale ones
    }

    /** Delete every stroke on page [pageIndex] hit by the eraser disc; returns true if any went. */
    private fun eraseStrokes(pageIndex: Int, px: Double, py: Double, radius: Double): Boolean {
        val page = doc.pages.getOrNull(pageIndex) ?: return false
        var removed = false
        val layers = page.layers.map { layer ->
            val kept = layer.elements.filterNot { it is Stroke && StrokeHitTester.hits(it, px, py, radius) }
            if (kept.size != layer.elements.size) removed = true
            if (kept.size == layer.elements.size) layer else Layer(kept)
        }
        if (!removed) return false
        val pages = doc.pages.toMutableList()
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        relayout()
        return true
    }

    // --- surface + rendering -------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        relayout(); render()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun relayout() {
        layout = PageStacker.stack(doc.pages, width, GAP_PX, zoom)
        scrollY = scrollY.coerceIn(0f, maxScrollY())
        scrollX = scrollX.coerceIn(0f, maxScrollX())
    }

    private fun render() {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(BACKDROP)
            for (box in layout.visible(scrollY, height.toFloat())) {
                BackgroundRenderer.draw(canvas, box, scrollX, scrollY)
                drawPageElements(canvas, box)
            }
            drawCurrent(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawPageElements(canvas: Canvas, box: PageBox) {
        val offsetX = box.leftPx - scrollX
        val offsetY = box.topPx - scrollY
        for (layer in box.page.layers) {
            for (element in layer.elements) {
                if (element is Stroke) {
                    drawPoints(canvas, element.points, element.tool, element.color, box.scale, offsetX, offsetY)
                } else {
                    elementRenderer.draw(canvas, element, box.scale, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawCurrent(canvas: Canvas) {
        val pts = current ?: return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        drawPoints(canvas, pts, tool, strokeColor(), box.scale, box.leftPx - scrollX, box.topPx - scrollY)
    }

    private fun drawPoints(canvas: Canvas, pts: List<StrokePoint>, tool: Tool, color: Int, scale: Float, offsetX: Float, offsetY: Float) {
        if (pts.size < 2) return
        paint.color = renderColor(tool, color)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = ((a.width + b.width) / 2.0).toFloat() * scale
            canvas.drawLine(
                offsetX + (a.x * scale).toFloat(), offsetY + (a.y * scale).toFloat(),
                offsetX + (b.x * scale).toFloat(), offsetY + (b.y * scale).toFloat(),
                paint,
            )
        }
    }

    /** Highlighter always paints translucent even if the stored colour is opaque. */
    private fun renderColor(tool: Tool, color: Int): Int =
        if (tool == Tool.HIGHLIGHTER && (color ushr 24) == 0xFF) (color and 0x00FFFFFF) or 0x80000000.toInt() else color

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val GAP_PX = 24f
        const val ERASER_RADIUS_PX = 18f
        const val BACKDROP = 0xFF3A3A3A.toInt()
        const val ZOOM_STEP = 1.25f
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 5f

        fun blankPage() = Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(AndroidColor.WHITE, "graph"), listOf(Layer(emptyList())))
    }
}
