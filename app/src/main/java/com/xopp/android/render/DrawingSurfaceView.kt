package com.xopp.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import kotlin.math.hypot

/** What a canvas tap places when a placement tool is active (see [DrawingSurfaceView.placeKind]). */
enum class PlaceKind { TEXT, IMAGE, TEX }

/**
 * Where a placement tap landed: the page and its page-local pt coordinates. [existingText] carries
 * the content of a text box the tap hit (so the editor opens it for editing instead of creating a
 * new one); null means "create new".
 */
data class Placement(val pageIndex: Int, val xPt: Double, val yPt: Double, val existingText: String? = null)

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

    /** Rasteriser for the PDF that backs this document's `pdf` pages (set on import), or null. */
    private var pdfSource: PdfPageCache? = null

    /** In-progress stroke (page-local pt space) and the page it belongs to. */
    private var current: ArrayList<StrokePoint>? = null
    private var currentPage = 0
    private var scrolling = false
    private var erasing = false
    private var placing = false
    private var placeDownX = 0f
    private var placeDownY = 0f
    private var lastFocusY = 0f
    private var lastFocusX = 0f

    /** The text box a placement tap hit, awaiting an edit-or-delete from the editor. */
    private var editingTarget: TextElement? = null
    /** Which page index was last reported to [onCurrentPageChanged], to suppress duplicate calls. */
    private var lastReportedPage = -1

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
    /** Notified with the index of the page nearest the viewport centre whenever it changes. */
    var onCurrentPageChanged: ((Int) -> Unit)? = null
    /** Notified when a placement tap lands, so the editor can prompt for content / pick an image. */
    var onPlace: ((PlaceKind, Placement) -> Unit)? = null

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f
    /** When true, one finger pans the canvas (the Hand tool) instead of drawing/erasing. */
    var handMode: Boolean = false
    /** When non-null, a one-finger tap places an element of this kind instead of drawing. */
    var placeKind: PlaceKind? = null

    private val strokePainter = StrokePainter()
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
        lastReportedPage = -1
        relayout()
        render()
    }

    /** The current working document — every page, layer, and preserved element, ready to save. */
    fun toDocument(): Document = doc

    /**
     * Supply the PDF whose pages back this document's `pdf` backgrounds (set on import), or null to
     * clear it (opening a plain `.xopp`). Closes any previously-held rasteriser.
     */
    fun setPdfSource(source: PdfPageCache?) {
        if (source === pdfSource) return
        pdfSource?.close()
        pdfSource = source
    }

    /** Flatten the current document (backgrounds, PDF pages, and all annotations) to a PDF. */
    fun exportPdf(out: java.io.OutputStream) = PdfExporter(pdfSource).export(doc, out)

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

    /** Scroll so page [index]'s top aligns with the viewport top (used by the page navigator). */
    fun goToPage(index: Int) {
        val box = layout.boxes.getOrNull(index) ?: return
        scrollY = box.topPx.coerceIn(0f, maxScrollY())
        render()
    }

    /** Emit [onCurrentPageChanged] if the page under the viewport centre changed since last time. */
    private fun reportCurrentPage() {
        if (doc.pages.isEmpty()) return
        val idx = currentPageIndex()
        if (idx != lastReportedPage) {
            lastReportedPage = idx
            onCurrentPageChanged?.invoke(idx)
        }
    }

    // --- authoring: place text boxes, images, and LaTeX images by tapping ------------------------

    /** Create a text box (or edit the one a tap hit) at the placement; blank content deletes it. */
    fun insertText(p: Placement, content: String, sizePt: Double, colorArgb: Int) {
        val target = editingTarget
        editingTarget = null
        if (content.isBlank()) {
            if (target != null) replaceElement(target, null)
            return
        }
        val text = TextElement("Sans", sizePt, p.xPt, p.yPt, colorArgb, content)
        if (target != null) replaceElement(target, text) else addElement(p.pageIndex, text)
    }

    /** Place a LaTeX image at the placement, sized to a default box (resizable later). */
    fun insertTex(p: Placement, latex: String, colorArgb: Int) {
        if (latex.isBlank()) return
        addElement(p.pageIndex, TexImageElement(p.xPt, p.yPt, p.xPt + TEX_W_PT, p.yPt + TEX_H_PT, latex, colorArgb))
    }

    /** Place an encoded image (PNG/JPEG bytes) at the placement, scaled to fit a default extent. */
    fun insertImage(p: Placement, data: ByteArray) {
        val (wPt, hPt) = imageBoxPt(data)
        addElement(p.pageIndex, ImageElement(p.xPt, p.yPt, p.xPt + wPt, p.yPt + hPt, data))
    }

    /** Discard a pending text-edit target (the editor's dialog was dismissed without saving). */
    fun cancelTextEdit() { editingTarget = null }

    /** The natural pt size for an image, scaled so its longest side is [IMG_MAX_PT]. */
    private fun imageBoxPt(data: ByteArray): Pair<Double, Double> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val w = opts.outWidth.coerceAtLeast(1)
        val h = opts.outHeight.coerceAtLeast(1)
        val s = IMG_MAX_PT / maxOf(w, h)
        return w * s to h * s
    }

    /** Append [element] to the top layer of page [pageIndex] as one undoable edit. */
    private fun addElement(pageIndex: Int, element: Element) {
        val before = doc
        val pages = doc.pages.toMutableList()
        val page = pages.getOrNull(pageIndex) ?: return
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val top = layers.lastIndex
        layers[top] = Layer(layers[top].elements + element)
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Replace [old] (matched by identity) with [new], or remove it when [new] is null; undoable. */
    private fun replaceElement(old: Element, new: Element?) {
        val before = doc
        var changed = false
        val pages = doc.pages.map { page ->
            page.copy(layers = page.layers.map { layer ->
                val idx = layer.elements.indexOfFirst { it === old }
                if (idx < 0) return@map layer
                changed = true
                val els = layer.elements.toMutableList()
                if (new == null) els.removeAt(idx) else els[idx] = new
                Layer(els)
            })
        }
        if (!changed) return
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** The top-most text box whose (approximate) bounds contain the point, or null. */
    private fun pickText(pageIndex: Int, xPt: Double, yPt: Double): TextElement? {
        val page = doc.pages.getOrNull(pageIndex) ?: return null
        for (layer in page.layers.asReversed()) {
            for (el in layer.elements.asReversed()) {
                if (el is TextElement && hitsText(el, xPt, yPt)) return el
            }
        }
        return null
    }

    /** Rough hit test for a text box from its content extent (glyph widths aren't measured here). */
    private fun hitsText(t: TextElement, xPt: Double, yPt: Double): Boolean {
        val lines = t.content.split("\n")
        val h = lines.size * t.size * 1.3
        val w = (lines.maxOfOrNull { it.length } ?: 1) * t.size * 0.62
        return xPt >= t.x - 4 && xPt <= t.x + w + 4 && yPt >= t.y - 4 && yPt <= t.y + h + 4
    }

    // --- touch: one finger draws (or erases), two fingers scroll -------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> when {
                placeKind != null -> beginPlace(event)
                handMode -> beginScroll(event)
                tool == Tool.ERASER -> startErase(event)
                else -> startStroke(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> beginScroll(event)
            MotionEvent.ACTION_MOVE -> when {
                scrolling -> doScroll(event)
                erasing -> eraseMove(event)
                placing -> placeMove(event)
                else -> extendStroke(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastFocusY = focusY(event, skip = event.actionIndex)
                lastFocusX = focusX(event, skip = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> endGesture()
            MotionEvent.ACTION_CANCEL -> { current = null; scrolling = false; erasing = false; placing = false; gestureStartDoc = null }
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

    /** A one-finger tap in a placement tool: remember where it went down; a small drag cancels it. */
    private fun beginPlace(event: MotionEvent) {
        scrolling = false
        erasing = false
        current = null
        placing = true
        placeDownX = event.x
        placeDownY = event.y
    }

    /** Moving past the tap slop turns a placement into a no-op (the user is scrubbing, not tapping). */
    private fun placeMove(event: MotionEvent) {
        if (hypot(event.x - placeDownX, event.y - placeDownY) > TAP_SLOP_PX) placing = false
    }

    /** Fire [onPlace] for the page/point the tap landed on, hitting an existing text box if any. */
    private fun commitPlace() {
        val kind = placeKind ?: return
        val box = layout.pageAt(placeDownY + scrollY) ?: return
        val xPt = ((placeDownX + scrollX - box.leftPx) / box.scale).toDouble()
        val yPt = ((placeDownY + scrollY - box.topPx) / box.scale).toDouble()
        val existing = if (kind == PlaceKind.TEXT) pickText(box.index, xPt, yPt)?.also { editingTarget = it }?.content else null
        onPlace?.invoke(kind, Placement(box.index, xPt, yPt, existing))
    }

    /** A second finger (or the Hand tool) started panning: abandon any partial stroke/erase/place. */
    private fun beginScroll(event: MotionEvent) {
        current = null
        erasing = false
        placing = false
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
        when {
            placing -> commitPlace()
            !scrolling && !erasing -> commitCurrent()
        }
        scrolling = false
        erasing = false
        placing = false
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
                BackgroundRenderer.draw(canvas, box, scrollX, scrollY, pdfBitmapFor(box))
                drawPageElements(canvas, box)
            }
            drawCurrent(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        reportCurrentPage()
    }

    /** The rasterised background for a `pdf`-backed page at its on-screen width, or null. */
    private fun pdfBitmapFor(box: PageBox): Bitmap? {
        val bg = box.page.background as? Background.Pdf ?: return null
        return pdfSource?.render(bg.pageNo, box.widthPx.toInt())
    }

    private fun drawPageElements(canvas: Canvas, box: PageBox) {
        PageRenderer.drawElements(
            canvas, box.page, box.scale, box.leftPx - scrollX, box.topPx - scrollY, strokePainter, elementRenderer,
        )
    }

    private fun drawCurrent(canvas: Canvas) {
        val pts = current ?: return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        strokePainter.draw(canvas, pts, tool, strokeColor(), box.scale, box.leftPx - scrollX, box.topPx - scrollY)
    }

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val GAP_PX = 24f
        const val ERASER_RADIUS_PX = 18f
        const val BACKDROP = 0xFF3A3A3A.toInt()
        const val ZOOM_STEP = 1.25f
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 5f
        const val TAP_SLOP_PX = 16f
        const val TEX_W_PT = 120.0
        const val TEX_H_PT = 40.0
        const val IMG_MAX_PT = 240.0

        fun blankPage() = Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(AndroidColor.WHITE, "graph"), listOf(Layer(emptyList())))
    }
}
