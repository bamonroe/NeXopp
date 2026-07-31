package com.xopp.android.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
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
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a canvas tap places when a placement tool is active (see [DrawingSurfaceView.placeKind]). */
enum class PlaceKind { TEXT, IMAGE, TEX }

/**
 * Where a placement tap landed: the page and its page-local pt coordinates. [existing] carries the
 * text box the tap hit (so the editor opens it for editing — prefilling its content, font, size and
 * colour — instead of creating a new one); null means "create new".
 */
data class Placement(val pageIndex: Int, val xPt: Double, val yPt: Double, val existing: TextElement? = null)

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
    /** Pointer id owning the current draw/erase gesture, so a resting palm can't perturb it. */
    private var gesturePointerId = -1
    /** True while a stylus/eraser tip owns the current draw/erase gesture (drives palm rejection). */
    private var stylusOwner = false
    // Hover preview position (view px) and whether a stylus is currently hovering.
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var scrolling = false
    private var erasing = false
    private var placing = false
    private var placeDownX = 0f
    private var placeDownY = 0f
    private var lastFocusY = 0f
    private var lastFocusX = 0f

    // Momentum scrolling: a released pan keeps gliding, decelerating, until it stalls or hits a bound.
    private val fling = Fling()
    /** Scales the release velocity fed into a fling; 1 = as-flung, 0 disables momentum. From the
     * momentum-strength setting via [MomentumStrength.factor]. */
    var flingStrength = MomentumStrength.NORMAL.factor
    private val choreographer = Choreographer.getInstance()
    private var flinging = false
    private var flingLastFrameNanos = 0L
    /** Release velocity of the just-ended pan (content-space px/s), captured on the final ACTION_UP. */
    private var releaseVx = 0f
    private var releaseVy = 0f
    /** Estimates the pan's velocity from its focus samples so [endGesture] can launch a fling. */
    private val velocityEstimator = VelocityEstimator()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val flingCallback = Choreographer.FrameCallback { frameTimeNanos -> onFlingFrame(frameTimeNanos) }

    // Hand-tool double-tap: a centre double-tap toggles full-page view, a left/right-edge double-tap
    // pages back/forward. Detected manually (single-finger tap = down→up without exceeding tap slop).
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlopPx = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    /** The current single touch's down time/position, and whether it has moved past tap slop (→ a pan). */
    private var handTapDownTime = 0L
    private var handTapDownX = 0f
    private var handTapDownY = 0f
    private var handTapCandidate = false
    private var handTapMoved = false
    /** The previous confirmed tap's down time/position, to match the next tap against for a double-tap. */
    private var handFirstTapTime = 0L
    private var handFirstTapX = 0f
    private var handFirstTapY = 0f

    /** The text box a placement tap hit, awaiting an edit-or-delete from the editor. */
    private var editingTarget: TextElement? = null
    /** Which page index was last reported to [onCurrentPageChanged], to suppress duplicate calls. */
    private var lastReportedPage = -1
    /** Last (scrollY, totalHeightPx, viewportPx) reported to [onScrollChanged], to suppress duplicate calls. */
    private var lastScrollReport = Triple(-1f, -1f, -1f)

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
    /** Notified with (scrollY, totalHeightPx, viewportPx) whenever the vertical scroll or content extent changes — drives the right-edge scroll thumb. */
    var onScrollChanged: ((Float, Float, Float) -> Unit)? = null
    /** Notified when a placement tap lands, so the editor can prompt for content / pick an image. */
    var onPlace: ((PlaceKind, Placement) -> Unit)? = null
    /** Notified when the Hand tool receives a centre double-tap, so the editor can toggle full-page (chrome-hidden) view. */
    var onToggleFullPage: (() -> Unit)? = null

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f
    /** Input-layer settings (finger-draw / barrel action) consulted by [InputClassifier]; from Settings. */
    var inputSettings: InputSettings = InputSettings()
    /** Pressure→width exponent (see [PressureCurve]); 1 = linear. Set from the sensitivity setting. */
    var pressureGamma: Float = PressureSensitivity.LINEAR.gamma
    /** When true, a hovering stylus shows a preview dot (from `ACTION_HOVER_MOVE`). */
    var showHover: Boolean = true
    /** When true, one finger pans the canvas (the Hand tool) instead of drawing/erasing. */
    var handMode: Boolean = false
    /** When non-null, a one-finger tap places an element of this kind instead of drawing. */
    var placeKind: PlaceKind? = null
    /** When true, one finger rubber-band-selects (or moves an existing selection) instead of drawing. */
    var selectMode: Boolean = false
        set(value) {
            field = value
            if (!value) clearSelection()
        }

    /** When true, the Select tool's marquee is a free-form lasso instead of a rectangle. */
    var lassoMode: Boolean = false

    /** Notified whenever the selection appears or clears, so the chrome can show contextual actions. */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** Notified when the copy/cut clipboard gains or loses content (drives the Paste affordance). */
    var onClipboardChanged: ((Boolean) -> Unit)? = null

    /** The current selection (a page index + the refs of its selected elements), or null. */
    private var selection: ActiveSelection? = null

    /** Copied/cut elements, ready to paste onto the visible page. */
    private var clipboard: List<Element> = emptyList()

    // Live rubber-band marquee (view px) and the page it selects within.
    private var banding = false
    private var bandPage = 0
    private var bandX0 = 0f
    private var bandY0 = 0f
    private var bandX1 = 0f
    private var bandY1 = 0f
    // Free-form lasso path (view px, x/y interleaved) captured while banding in lasso mode.
    private val lassoPts = ArrayList<Float>()

    // Live move of the current selection (also the base snapshot for resize/rotate, so a live
    // transform recomputes from the gesture-start document each frame and never drifts).
    private var movingSel = false
    private var moveStartDoc: Document? = null
    private var moveStartPtX = 0.0
    private var moveStartPtY = 0.0

    // Live uniform resize about a fixed anchor (the opposite corner), in page-local pt.
    private var resizing = false
    private var resizeAnchorX = 0.0
    private var resizeAnchorY = 0.0
    private var resizeStartDist = 1.0

    // Live rotate about the selection centre (pt); strokes only.
    private var rotating = false
    private var rotatePivotX = 0.0
    private var rotatePivotY = 0.0
    private var rotateStartAngle = 0.0

    private val strokePainter = StrokePainter()
    private val elementRenderer = ElementRenderer()

    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_COLOR
    }
    private val handleArmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
    }
    private val lassoPath = Path()
    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BAND_FILL
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        holder.addCallback(this)
    }

    /** Replace the canvas contents with [doc] (all pages, layers, and unmodelled elements). */
    fun load(doc: Document) {
        this.doc = if (doc.pages.isEmpty()) doc.copy(pages = listOf(blankPage())) else doc
        scrollY = 0f
        scrollX = 0f
        selection = null
        onSelectionChanged?.invoke(false)
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

    /** Set the vertical scroll offset to [y] px from the top, clamped (driven by the right-edge scroll thumb). */
    fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY())
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

    /** Emit [onScrollChanged] if the scroll offset or content extent changed since last time. */
    private fun reportScroll() {
        val t = Triple(scrollY, layout.totalHeightPx, height.toFloat())
        if (t != lastScrollReport) {
            lastScrollReport = t
            onScrollChanged?.invoke(t.first, t.second, t.third)
        }
    }

    // --- authoring: place text boxes, images, and LaTeX images by tapping ------------------------

    /** Create a text box (or edit the one a tap hit) at the placement; blank content deletes it. */
    fun insertText(p: Placement, content: String, font: String, sizePt: Double, colorArgb: Int) {
        val target = editingTarget
        editingTarget = null
        if (content.isBlank()) {
            if (target != null) replaceElement(target, null)
            return
        }
        val text = TextElement(font, sizePt, p.xPt, p.yPt, colorArgb, content)
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

    // --- selection: rubber-band / tap to select, drag to move, delete --------------------------

    /** A live selection: the page it lives on and the position-addressed elements it holds. */
    private data class ActiveSelection(val pageIndex: Int, val refs: Set<ElementRef>)

    /**
     * Down in Select mode. With a live selection, a touch near a corner **resizes**, near the top
     * rotate knob (strokes only) **rotates**, and inside the outline **moves**; otherwise it starts
     * a new rubber-band (or lasso). The handle hit-tests run in view px against the drawn outline.
     */
    private fun beginSelect(event: MotionEvent) {
        scrolling = false
        erasing = false
        placing = false
        current = null
        val sel = selection
        if (sel != null && dispatchHandle(event, sel)) return
        beginBand(event)
    }

    /** Try to start a resize/rotate/move for [sel] from a down at (event.x, event.y); else false. */
    private fun dispatchHandle(event: MotionEvent, sel: ActiveSelection): Boolean {
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return false
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return false
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return false
        // Rotate knob (top-centre), offered only when every selected element is a stroke.
        if (isAllStrokes(sel)) {
            val midX = ((b.left + b.right) / 2 * box.scale + box.leftPx - scrollX).toFloat()
            val topY = (b.top * box.scale + box.topPx - scrollY).toFloat() - SELECT_PAD_PX - ROTATE_ARM_PX
            if (hypot(event.x - midX, event.y - topY) <= HANDLE_HIT_PX) {
                beginRotate(event, box, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
                return true
            }
        }
        // Four corner resize handles; the anchor is the diagonally-opposite corner.
        val cornersPt = arrayOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
        for (i in 0..3) {
            val padX = if (cornersPt[i].first == b.left) -SELECT_PAD_PX else SELECT_PAD_PX
            val padY = if (cornersPt[i].second == b.top) -SELECT_PAD_PX else SELECT_PAD_PX
            val hx = (cornersPt[i].first * box.scale + box.leftPx - scrollX).toFloat() + padX
            val hy = (cornersPt[i].second * box.scale + box.topPx - scrollY).toFloat() + padY
            if (hypot(event.x - hx, event.y - hy) <= HANDLE_HIT_PX) {
                val anchor = cornersPt[(i + 2) % 4]
                beginResize(event, box, anchor.first, anchor.second)
                return true
            }
        }
        // Inside the outline: move.
        if (b.expand(MOVE_GRAB_PAD).contains(ptX(box, event.x), ptY(box, event.y))) {
            beginMove(event, sel, box)
            return true
        }
        return false
    }

    /** True if every element in [sel] is a stroke (the only element whose rotation round-trips). */
    private fun isAllStrokes(sel: ActiveSelection): Boolean {
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return false
        return sel.refs.isNotEmpty() && sel.refs.all {
            page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) is Stroke
        }
    }

    private fun beginMove(event: MotionEvent, sel: ActiveSelection, box: PageBox) {
        movingSel = true
        gestureStartDoc = doc
        moveStartDoc = doc
        moveStartPtX = ptX(box, event.x)
        moveStartPtY = ptY(box, event.y)
    }

    private fun beginResize(event: MotionEvent, box: PageBox, anchorX: Double, anchorY: Double) {
        resizing = true
        gestureStartDoc = doc
        moveStartDoc = doc
        resizeAnchorX = anchorX
        resizeAnchorY = anchorY
        resizeStartDist = hypot(ptX(box, event.x) - anchorX, ptY(box, event.y) - anchorY).coerceAtLeast(1e-3)
    }

    /** Resize the selection: a uniform scale by (current distance / start distance) about the anchor. */
    private fun resizeSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val dist = hypot(ptX(box, event.x) - resizeAnchorX, ptY(box, event.y) - resizeAnchorY)
        val factor = (dist / resizeStartDist).coerceIn(MIN_RESIZE, MAX_RESIZE)
        doc = doc.copy(pages = SelectionOps.scale(start.pages, sel.pageIndex, sel.refs, factor, resizeAnchorX, resizeAnchorY))
        relayout()
        render()
    }

    private fun beginRotate(event: MotionEvent, box: PageBox, pivotX: Double, pivotY: Double) {
        rotating = true
        gestureStartDoc = doc
        moveStartDoc = doc
        rotatePivotX = pivotX
        rotatePivotY = pivotY
        rotateStartAngle = atan2(ptY(box, event.y) - pivotY, ptX(box, event.x) - pivotX)
    }

    /** Rotate the selection's strokes by the angle swept around the pivot since the gesture start. */
    private fun rotateSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val angle = atan2(ptY(box, event.y) - rotatePivotY, ptX(box, event.x) - rotatePivotX) - rotateStartAngle
        doc = doc.copy(pages = SelectionOps.rotate(start.pages, sel.pageIndex, sel.refs, angle, rotatePivotX, rotatePivotY))
        relayout()
        render()
    }

    /**
     * Finish a move: if the selection's centre now sits over a different page, re-home the elements
     * onto that page's top layer (mapping through both pages' pt frames so they land where they're
     * dropped). The whole move records as one undoable edit via [finishGesture].
     */
    private fun commitMove() {
        val sel = selection ?: return
        val srcBox = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return
        val centreYContent = ((b.top + b.bottom) / 2 * srcBox.scale + srcBox.topPx).toFloat()
        val target = layout.pageAt(centreYContent) ?: return
        if (target.index == sel.pageIndex) return
        val s = srcBox.scale.toDouble() / target.scale.toDouble()
        val dx = (srcBox.leftPx - target.leftPx) / target.scale.toDouble()
        val dy = (srcBox.topPx - target.topPx) / target.scale.toDouble()
        val (pages, refs) = SelectionOps.moveToPage(doc.pages, sel.pageIndex, target.index, sel.refs, s, dx, dy)
        doc = doc.copy(pages = pages)
        selection = ActiveSelection(target.index, refs)
    }

    /** Drag the selection: translate the elements from their gesture-start positions, live. */
    private fun moveSelect(event: MotionEvent) {
        val sel = selection ?: return
        val start = moveStartDoc ?: return
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val dx = ptX(box, event.x) - moveStartPtX
        val dy = ptY(box, event.y) - moveStartPtY
        doc = doc.copy(pages = SelectionOps.translate(start.pages, sel.pageIndex, sel.refs, dx, dy))
        relayout()
        render()
    }

    private fun beginBand(event: MotionEvent) {
        if (selection != null) {
            selection = null
            onSelectionChanged?.invoke(false)
        }
        banding = true
        bandPage = layout.pageAt(event.y + scrollY)?.index ?: 0
        bandX0 = event.x; bandY0 = event.y
        bandX1 = event.x; bandY1 = event.y
        lassoPts.clear()
        if (lassoMode) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    private fun bandMove(event: MotionEvent) {
        bandX1 = event.x
        bandY1 = event.y
        if (lassoMode) { lassoPts.add(event.x); lassoPts.add(event.y) }
        render()
    }

    /**
     * Up from a marquee: a tap (no real drag) picks the topmost element; a rectangle drag selects
     * everything wholly enclosed; a lasso drag selects everything wholly inside the traced polygon.
     */
    private fun commitBand() {
        val box = layout.boxes.getOrNull(bandPage) ?: return
        val page = doc.pages.getOrNull(bandPage) ?: return
        val isTap = hypot(bandX1 - bandX0, bandY1 - bandY0) <= TAP_SLOP_PX
        val refs: Set<ElementRef> = when {
            isTap -> SelectionTester.pickTopmost(page, ptX(box, bandX0), ptY(box, bandY0))?.let { setOf(it) } ?: emptySet()
            lassoMode -> {
                val poly = ArrayList<Vec2>(lassoPts.size / 2)
                var i = 0
                while (i < lassoPts.size) { poly.add(Vec2(ptX(box, lassoPts[i]), ptY(box, lassoPts[i + 1]))); i += 2 }
                SelectionTester.inPolygon(page, poly)
            }
            else -> {
                val rect = Bounds(
                    min(ptX(box, bandX0), ptX(box, bandX1)), min(ptY(box, bandY0), ptY(box, bandY1)),
                    max(ptX(box, bandX0), ptX(box, bandX1)), max(ptY(box, bandY0), ptY(box, bandY1)),
                )
                SelectionTester.inRect(page, rect)
            }
        }
        selection = if (refs.isEmpty()) null else ActiveSelection(bandPage, refs)
        onSelectionChanged?.invoke(selection != null)
        render()
    }

    /** Delete every selected element as one undoable edit. */
    fun deleteSelection() {
        val sel = selection ?: return
        val before = doc
        doc = doc.copy(pages = SelectionOps.delete(doc.pages, sel.pageIndex, sel.refs))
        selection = null
        onSelectionChanged?.invoke(false)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Drop the current selection (a view-only change; not recorded in history). */
    fun clearSelection() {
        if (selection == null) return
        selection = null
        onSelectionChanged?.invoke(false)
        render()
    }

    /** Recolour and/or re-width the selected elements as one undoable edit (selection stays). */
    fun restyleSelection(color: Int?, widthPt: Double?) {
        val sel = selection ?: return
        val before = doc
        val pages = SelectionOps.restyle(doc.pages, sel.pageIndex, sel.refs, color, widthPt)
        if (pages === doc.pages) return
        doc = doc.copy(pages = pages)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** Copy the selected elements to the clipboard (leaves the document and selection unchanged). */
    fun copySelection() {
        val sel = selection ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        clipboard = SelectionOps.elementsAt(page, sel.refs)
        onClipboardChanged?.invoke(clipboard.isNotEmpty())
    }

    /** Copy then delete the selection (one undoable edit via [deleteSelection]). */
    fun cutSelection() {
        if (selection == null) return
        copySelection()
        deleteSelection()
    }

    /** Whether the clipboard currently holds anything to paste. */
    fun hasClipboard(): Boolean = clipboard.isNotEmpty()

    /** Paste the clipboard onto the visible page (offset a little), selecting the pasted copies. */
    fun pasteClipboard() {
        if (clipboard.isEmpty()) return
        val target = visiblePageIndex()
        pasteOnto(target, clipboard.map { SelectionOps.translate(it, PASTE_OFFSET_PT, PASTE_OFFSET_PT) })
    }

    /** Duplicate the selection in place (offset a little), selecting the duplicates. */
    fun duplicateSelection() {
        val sel = selection ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val copies = SelectionOps.elementsAt(page, sel.refs).map { SelectionOps.translate(it, PASTE_OFFSET_PT, PASTE_OFFSET_PT) }
        pasteOnto(sel.pageIndex, copies)
    }

    /** Append [elements] to [pageIndex]'s top layer as one undoable edit and select them. */
    private fun pasteOnto(pageIndex: Int, elements: List<Element>) {
        if (elements.isEmpty()) return
        val before = doc
        val (pages, refs) = SelectionOps.addToTopLayer(doc.pages, pageIndex, elements)
        if (refs.isEmpty()) return
        doc = doc.copy(pages = pages)
        selection = ActiveSelection(pageIndex, refs)
        onSelectionChanged?.invoke(true)
        history.record(before)
        notifyHistory()
        relayout()
        render()
    }

    /** The page nearest the viewport centre — the target for a paste. */
    private fun visiblePageIndex(): Int = layout.pageAt(scrollY + height / 2f)?.index ?: currentPage

    /** View px -> page-local pt for [box]. */
    private fun ptX(box: PageBox, vx: Float): Double = ((vx + scrollX - box.leftPx) / box.scale).toDouble()
    private fun ptY(box: PageBox, vy: Float): Double = ((vy + scrollY - box.topPx) / box.scale).toDouble()

    // --- touch: the pen draws (or erases), fingers pan; input is routed through InputClassifier ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stopFling(); beginPointer(event, 0); beginHandTap(event) }
            MotionEvent.ACTION_POINTER_DOWN -> { handTapCandidate = false; onPointerDown(event) }
            MotionEvent.ACTION_MOVE -> {
                if (handTapCandidate) trackHandTapMove(event)
                when {
                    scrolling -> doScroll(event)
                    erasing -> eraseMove(event)
                    placing -> placeMove(event)
                    resizing -> resizeSelect(event)
                    rotating -> rotateSelect(event)
                    movingSel -> moveSelect(event)
                    banding -> bandMove(event)
                    current != null -> extendStroke(event)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> { captureReleaseVelocity(event); handleHandTapUp(event); endGesture() }
            MotionEvent.ACTION_CANCEL -> { handTapCandidate = false; cancelGesture() }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** Fold the release focus into the estimator and read out the release velocity (content moves
     * opposite the finger), clamped to the platform's max fling speed. */
    private fun captureReleaseVelocity(event: MotionEvent) {
        velocityEstimator.add(event.eventTime, focusX(event, skip = -1), focusY(event, skip = -1))
        val v = velocityEstimator.velocity()
        releaseVx = (-v.vx).coerceIn(-maxFlingVelocity, maxFlingVelocity)
        releaseVy = (-v.vy).coerceIn(-maxFlingVelocity, maxFlingVelocity)
    }

    /** Arm double-tap tracking for a fresh single-finger touch, but only while the Hand tool is active. */
    private fun beginHandTap(event: MotionEvent) {
        handTapCandidate = handMode
        handTapMoved = false
        handTapDownTime = event.eventTime
        handTapDownX = event.x
        handTapDownY = event.y
    }

    /** A moved-too-far touch is a pan, not a tap — disqualify it from forming a double-tap. */
    private fun trackHandTapMove(event: MotionEvent) {
        if (hypot(event.x - handTapDownX, event.y - handTapDownY) > doubleTapSlopPx) handTapMoved = true
    }

    /** On lift, confirm a tap and — if it pairs with the previous one in time and place — fire the double-tap. */
    private fun handleHandTapUp(event: MotionEvent) {
        if (!handTapCandidate) return
        handTapCandidate = false
        if (handTapMoved) { handFirstTapTime = 0L; return }
        val pairsWithPrevious = handFirstTapTime != 0L &&
            handTapDownTime - handFirstTapTime <= doubleTapTimeoutMs &&
            hypot(handTapDownX - handFirstTapX, handTapDownY - handFirstTapY) <= doubleTapSlopPx
        if (pairsWithPrevious) {
            handFirstTapTime = 0L // consume, so a third tap doesn't immediately re-fire
            onHandDoubleTap(handTapDownX)
        } else {
            handFirstTapTime = handTapDownTime
            handFirstTapX = handTapDownX
            handFirstTapY = handTapDownY
        }
    }

    /** Route a Hand-tool double-tap by horizontal zone: left third → prev page, right third → next, centre → toggle full-page. */
    private fun onHandDoubleTap(x: Float) {
        val edge = width / 3f
        when {
            x < edge -> goToPage(currentPageIndex() - 1)
            x > width - edge -> goToPage(currentPageIndex() + 1)
            else -> onToggleFullPage?.invoke()
        }
    }

    /** A hovering stylus (tip not yet down) drives a preview dot so the user can see where it'll land. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val kind = pointerKindOf(event, 0)
        if (!showHover || (kind != PointerKind.STYLUS && kind != PointerKind.ERASER_TIP)) {
            if (hovering) { hovering = false; render() }
            return super.onHoverEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true; hoverX = event.x; hoverY = event.y; render()
            }
            MotionEvent.ACTION_HOVER_EXIT -> { hovering = false; render() }
        }
        return true
    }

    /** Map one event pointer's tool type to our device-independent [PointerKind]. */
    private fun pointerKindOf(event: MotionEvent, pointerIndex: Int): PointerKind =
        when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
            MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
            MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
            else -> PointerKind.UNKNOWN
        }

    private fun barrelPressed(event: MotionEvent): Boolean =
        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    /** The on-screen tool collapsed to the classifier's [ActiveTool]. */
    private fun activeTool(): ActiveTool = when {
        placeKind != null -> ActiveTool.PLACE
        handMode -> ActiveTool.HAND
        selectMode -> ActiveTool.SELECT
        tool == Tool.ERASER -> ActiveTool.ERASER
        tool == Tool.HIGHLIGHTER -> ActiveTool.HIGHLIGHTER
        else -> ActiveTool.PEN
    }

    /** Begin a gesture for the pointer at [pointerIndex], its intent decided by [InputClassifier]. */
    private fun beginPointer(event: MotionEvent, pointerIndex: Int) {
        val kind = pointerKindOf(event, pointerIndex)
        val intent = InputClassifier.classify(kind, barrelPressed(event), activeTool(), inputSettings)
        stylusOwner = (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) &&
            (intent == GestureIntent.DRAW || intent == GestureIntent.ERASE)
        when (intent) {
            GestureIntent.PLACE -> beginPlace(event, pointerIndex)
            GestureIntent.PAN -> beginScroll(event)
            GestureIntent.SELECT -> beginSelect(event)
            GestureIntent.ERASE -> startErase(event, pointerIndex)
            GestureIntent.DRAW -> startStroke(event, pointerIndex)
            GestureIntent.IGNORE -> Unit
        }
    }

    /**
     * A second pointer arrived. A stylus/eraser tip takes over any in-progress finger gesture (so a
     * palm that landed first can't block the pen); while a stylus already owns the gesture, extra
     * finger/palm pointers are ignored (palm rejection); otherwise two fingers pan.
     */
    private fun onPointerDown(event: MotionEvent) {
        val idx = event.actionIndex
        val kind = pointerKindOf(event, idx)
        when {
            kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP -> {
                abandonInProgress()
                beginPointer(event, idx)
            }
            stylusOwner -> Unit // palm / finger resting while the pen writes: ignore
            else -> beginScroll(event) // ordinary two-finger pan
        }
    }

    /** If the pointer that lifted owns the draw/erase gesture, finish it; otherwise keep panning. */
    private fun onPointerUp(event: MotionEvent) {
        val upId = event.getPointerId(event.actionIndex)
        if (upId == gesturePointerId && (current != null || erasing)) {
            endGesture()
            return
        }
        lastFocusY = focusY(event, skip = event.actionIndex)
        lastFocusX = focusX(event, skip = event.actionIndex)
        // The focus jumps when a finger leaves, so restart the estimate from the new baseline —
        // otherwise that discontinuity would read as a huge phantom flick.
        velocityEstimator.reset()
        velocityEstimator.add(event.eventTime, lastFocusX, lastFocusY)
    }

    /** Drop any in-progress draw/erase/place/band/move without committing (a stylus is taking over). */
    private fun abandonInProgress() {
        current = null; erasing = false; placing = false; banding = false
        movingSel = false; resizing = false; rotating = false; scrolling = false
    }

    private fun cancelGesture() {
        stopFling()
        current = null; scrolling = false; erasing = false; placing = false
        movingSel = false; resizing = false; rotating = false; banding = false; gestureStartDoc = null
        gesturePointerId = -1; stylusOwner = false
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        gestureStartDoc = doc
        gesturePointerId = event.getPointerId(pointerIndex)
        val box = layout.pageAt(event.getY(pointerIndex) + scrollY) ?: run { current = null; return }
        currentPage = box.index
        current = ArrayList<StrokePoint>().also { addSamples(event, pointerIndex, box, it) }
    }

    private fun extendStroke(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        current?.let { addSamples(event, pointerIndex, box, it); render() }
    }

    /** The eraser: touch/drag deletes any stroke it passes over on the page under the pointer. */
    private fun startErase(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        erasing = true
        gestureStartDoc = doc
        gesturePointerId = event.getPointerId(pointerIndex)
        val box = layout.pageAt(event.getY(pointerIndex) + scrollY) ?: return
        currentPage = box.index
        eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun eraseMove(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun eraseAt(box: PageBox, vx: Float, vy: Float) {
        val px = ((vx + scrollX - box.leftPx) / box.scale).toDouble()
        val py = ((vy + scrollY - box.topPx) / box.scale).toDouble()
        val radius = ERASER_RADIUS_PX / box.scale
        if (eraseStrokes(currentPage, px, py, radius.toDouble())) render()
    }

    /** A tap in a placement tool: remember where it went down; a small drag cancels it. */
    private fun beginPlace(event: MotionEvent, pointerIndex: Int) {
        scrolling = false
        erasing = false
        current = null
        placing = true
        placeDownX = event.getX(pointerIndex)
        placeDownY = event.getY(pointerIndex)
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
        val existing = if (kind == PlaceKind.TEXT) pickText(box.index, xPt, yPt)?.also { editingTarget = it } else null
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
        velocityEstimator.reset()
        velocityEstimator.add(event.eventTime, lastFocusX, lastFocusY)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = focusY(event, skip = -1)
        val fx = focusX(event, skip = -1)
        scrollY = (scrollY + (lastFocusY - fy)).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + (lastFocusX - fx)).coerceIn(0f, maxScrollX())
        lastFocusY = fy
        lastFocusX = fx
        velocityEstimator.add(event.eventTime, fx, fy)
        render()
    }

    private fun maxScrollY(): Float = (layout.totalHeightPx - height).coerceAtLeast(0f)
    private fun maxScrollX(): Float = (layout.contentWidthPx - width).coerceAtLeast(0f)

    private fun endGesture() {
        // Snapshot then clear the mode flags first, so any render() inside a commit (e.g. the
        // rubber-band) sees the gesture already ended and doesn't paint a stale marquee/overlay.
        val wasScrolling = scrolling
        val wasErasing = erasing
        val wasPlacing = placing
        val wasMoving = movingSel
        val wasTransforming = resizing || rotating
        val wasBanding = banding
        scrolling = false
        erasing = false
        placing = false
        movingSel = false
        resizing = false
        rotating = false
        banding = false
        when {
            wasPlacing -> commitPlace()
            wasMoving -> commitMove() // translation applied live; re-home if dropped on another page
            wasTransforming -> Unit  // resize/rotate applied live; finishGesture records them
            wasBanding -> commitBand()
            !wasScrolling && !wasErasing -> commitCurrent()
        }
        moveStartDoc = null
        finishGesture()
        gesturePointerId = -1
        stylusOwner = false
        if (wasScrolling) startFlingIfFast()
    }

    /** Launch a decelerating glide from the just-captured release velocity, if it's fast enough. */
    private fun startFlingIfFast() {
        if (maxScrollY() <= 0f && maxScrollX() <= 0f) return // nothing to scroll
        fling.start(releaseVx * flingStrength, releaseVy * flingStrength)
        if (!fling.isMoving) return
        flinging = true
        flingLastFrameNanos = 0L
        choreographer.postFrameCallback(flingCallback)
    }

    /** One animation frame of the glide: decay velocity, scroll by the step, stop when done/stuck. */
    private fun onFlingFrame(frameTimeNanos: Long) {
        if (!flinging) return
        // First frame seeds the clock; later frames use the real elapsed time so the glide is
        // frame-rate independent. Clamp big gaps (e.g. after a stall) so one step can't teleport.
        val dt = if (flingLastFrameNanos == 0L) 0f
        else ((frameTimeNanos - flingLastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        flingLastFrameNanos = frameTimeNanos
        val step = fling.advance(dt)
        val prevY = scrollY
        val prevX = scrollX
        scrollY = (scrollY + step.dy).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + step.dx).coerceIn(0f, maxScrollX())
        render()
        // Stop once too slow, or when both axes are pinned at a bound (nowhere left to glide).
        val stuck = scrollY == prevY && scrollX == prevX && dt > 0f
        if (!fling.isMoving || stuck) stopFling() else choreographer.postFrameCallback(flingCallback)
    }

    /** Halt any in-flight glide (a new touch, a cancel, or detachment). */
    private fun stopFling() {
        if (flinging) {
            flinging = false
            choreographer.removeFrameCallback(flingCallback)
        }
        fling.stop()
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

    /**
     * Append every sample for the gesture's pointer — historical first — as pressure-scaled
     * page-local points. Sampling only [pointerIndex] (not pointer 0) is what lets a resting palm
     * coexist with the pen: the palm is a different pointer and is never read here.
     */
    private fun addSamples(event: MotionEvent, pointerIndex: Int, box: PageBox, into: MutableList<StrokePoint>) {
        for (h in 0 until event.historySize) {
            into += point(
                box,
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                event.getHistoricalPressure(pointerIndex, h),
            )
        }
        into += point(box, event.getX(pointerIndex), event.getY(pointerIndex), event.getPressure(pointerIndex))
    }

    private fun point(box: PageBox, vx: Float, vy: Float, pressure: Float): StrokePoint {
        val p = if (pressure <= 0f) 1f else pressure
        return StrokePoint(
            x = ((vx + scrollX - box.leftPx) / box.scale).toDouble(),
            y = ((vy + scrollY - box.topPx) / box.scale).toDouble(),
            width = (baseWidthPt * PressureCurve.factor(p, pressureGamma)).toDouble(),
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
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopFling()

    override fun onDetachedFromWindow() {
        stopFling()
        super.onDetachedFromWindow()
    }

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
            selection?.let { drawSelectionBox(canvas, it) }
            if (banding) drawBand(canvas)
            if (hovering && showHover && current == null) drawHover(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        reportCurrentPage()
        reportScroll()
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

    /** Draw the dashed selection outline (padded a little), the four resize handles, and — for an
     * all-stroke selection — the top rotate knob. */
    private fun drawSelectionBox(canvas: Canvas, sel: ActiveSelection) {
        val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
        val page = doc.pages.getOrNull(sel.pageIndex) ?: return
        val b = SelectionTester.boundsOf(page, sel.refs) ?: return
        val l = (b.left * box.scale + box.leftPx - scrollX).toFloat() - SELECT_PAD_PX
        val t = (b.top * box.scale + box.topPx - scrollY).toFloat() - SELECT_PAD_PX
        val r = (b.right * box.scale + box.leftPx - scrollX).toFloat() + SELECT_PAD_PX
        val bot = (b.bottom * box.scale + box.topPx - scrollY).toFloat() + SELECT_PAD_PX
        canvas.drawRect(l, t, r, bot, selectionFillPaint)
        canvas.drawRect(l, t, r, bot, selectionStrokePaint)
        // Corner resize handles.
        for (hx in floatArrayOf(l, r)) for (hy in floatArrayOf(t, bot)) {
            canvas.drawCircle(hx, hy, HANDLE_DRAW_PX, handlePaint)
        }
        // Rotate knob above the top edge (strokes only).
        if (isAllStrokes(sel)) {
            val midX = (l + r) / 2f
            val knobY = t - ROTATE_ARM_PX
            canvas.drawLine(midX, t, midX, knobY, handleArmPaint)
            canvas.drawCircle(midX, knobY, HANDLE_DRAW_PX, handlePaint)
        }
    }

    /** Draw the hover preview: a ring in the pen colour at the stylus's current hover point. */
    private fun drawHover(canvas: Canvas) {
        val r = (baseWidthPt * 3f).coerceIn(6f, 28f)
        hoverPaint.color = (colorArgb and 0x00FFFFFF) or HOVER_ALPHA
        canvas.drawCircle(hoverX, hoverY, r, hoverPaint)
    }

    /** Draw the live marquee (view px): a rectangle, or the traced lasso path in lasso mode. */
    private fun drawBand(canvas: Canvas) {
        if (lassoMode && lassoPts.size >= 4) {
            lassoPath.reset()
            lassoPath.moveTo(lassoPts[0], lassoPts[1])
            var i = 2
            while (i < lassoPts.size) { lassoPath.lineTo(lassoPts[i], lassoPts[i + 1]); i += 2 }
            lassoPath.close()
            canvas.drawPath(lassoPath, bandFillPaint)
            canvas.drawPath(lassoPath, selectionStrokePaint)
            return
        }
        val l = min(bandX0, bandX1)
        val t = min(bandY0, bandY1)
        val r = max(bandX0, bandX1)
        val bot = max(bandY0, bandY1)
        canvas.drawRect(l, t, r, bot, bandFillPaint)
        canvas.drawRect(l, t, r, bot, selectionStrokePaint)
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
        const val SELECT_PAD_PX = 6f
        const val MOVE_GRAB_PAD = 8.0
        const val HANDLE_HIT_PX = 30f      // touch radius for grabbing a resize/rotate handle
        const val HANDLE_DRAW_PX = 7f      // drawn radius of a handle dot
        const val ROTATE_ARM_PX = 40f      // gap from the top edge up to the rotate knob
        const val MIN_RESIZE = 0.05        // clamp on the live uniform-resize factor
        const val MAX_RESIZE = 20.0
        const val PASTE_OFFSET_PT = 12.0   // paste/duplicate nudge so copies don't hide the original
        const val SELECTION_COLOR = 0xFF2060E0.toInt()
        const val SELECTION_FILL = 0x142060E0
        const val BAND_FILL = 0x222060E0
        const val HOVER_ALPHA = 0xB0000000.toInt()
        const val TEX_W_PT = 120.0
        const val TEX_H_PT = 40.0
        const val IMG_MAX_PT = 240.0

        fun blankPage() = Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(AndroidColor.WHITE, "graph"), listOf(Layer(emptyList())))
    }
}
