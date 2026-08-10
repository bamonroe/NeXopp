/**
 * [DrawingSurfaceView]'s ink capture: the freehand stroke, the tap-placed spline, the eraser drag,
 * and the tap that places text/images — plus the sampling, pressure-to-width and commit helpers they
 * share. Extensions on the view, so they read its state directly.
 */
package com.xopp.android.render

import android.view.MotionEvent
import com.xopp.android.audio.audioRef
import com.xopp.android.audio.withAudio
import com.xopp.android.format.XoppColor
import com.xopp.android.format.XoppColor.withAlpha
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import kotlin.math.hypot

internal fun DrawingSurfaceView.startStroke(event: MotionEvent, pointerIndex: Int) {
    scrolling = false
    shaping = shapeKind != null
    gestureStartDoc = doc
    gesturePointerId = event.getPointerId(pointerIndex)
    val box = layout.pageAt(event.getX(pointerIndex) + scrollX, event.getY(pointerIndex) + scrollY)
        ?: run { current = null; return }
    currentPage = box.index
    if (shaping) {
        // Grid snap first, then the guide — a placed guide is the stronger constraint and must
        // not be undone by pulling the point back onto the ruling.
        val (sx, sy) = guided(
            box,
            snapX(box, box.toPtX(event.getX(pointerIndex), scrollX)),
            snapY(box, box.toPtY(event.getY(pointerIndex), scrollY)),
        )
        shapeStartX = sx
        shapeStartY = sy
        shapeWidthPt = widthForPressure(event.getPressure(pointerIndex))
        current = ArrayList(listOf(StrokePoint(shapeStartX, shapeStartY, shapeWidthPt)))
    } else {
        // Decimate against this page's real px/pt, so a stroke drawn zoomed out or in a
        // multi-column view keeps the same document-space detail as one drawn at 100%.
        smoother.reset(strokePrecision.stepPxFor(box.scale))
        current = ArrayList<StrokePoint>().also { addSamples(event, pointerIndex, box, it) }
    }
}

internal fun DrawingSurfaceView.extendStroke(event: MotionEvent) {
    val pointerIndex = event.findPointerIndex(gesturePointerId)
    if (pointerIndex < 0) return
    val box = layout.boxes.getOrNull(currentPage) ?: return
    if (shaping) {
        val (ex, ey) = guided(
            box,
            snapX(box, box.toPtX(event.getX(pointerIndex), scrollX)),
            snapY(box, box.toPtY(event.getY(pointerIndex), scrollY)),
        )
        current = ArrayList(
            ShapeBuilder.build(shapeKind ?: return, shapeStartX, shapeStartY, ex, ey, shapeWidthPt),
        )
        render()
    } else {
        current?.let { addSamples(event, pointerIndex, box, it); render() }
    }
}

// --- the spline tool: tap to add a control point, drag to curve it, double-tap to finish -------

/**
 * A touch while the spline tool is active. A tap that pairs with the previous one (double-tap)
 * closes the curve; otherwise it appends a control point, which the following drag can curve.
 */
internal fun DrawingSurfaceView.splineDown(event: MotionEvent, pointerIndex: Int) {
    val x = event.getX(pointerIndex)
    val y = event.getY(pointerIndex)
    if (splineNodes.isNotEmpty() && pairsWithPreviousSplineTap(event.eventTime, x, y)) {
        finishSpline()
        return
    }
    scrolling = false
    // The first node fixes the page for the whole curve, so later taps stay in one stroke.
    val box = if (splineNodes.isEmpty()) layout.pageAt(x + scrollX, y + scrollY) else layout.boxes.getOrNull(currentPage)
    if (box == null) return
    if (splineNodes.isEmpty()) {
        currentPage = box.index
        gestureStartDoc = doc
    }
    gesturePointerId = event.getPointerId(pointerIndex)
    if (splineNodes.isEmpty()) shapeWidthPt = widthForPressure(event.getPressure(pointerIndex))
    splineAnchorX = box.toPtX(x, scrollX)
    splineAnchorY = box.toPtY(y, scrollY)
    splineNodes += SplineNode(splineAnchorX, splineAnchorY)
    splineDragging = true
    splineTapTime = event.eventTime
    splineTapX = x
    splineTapY = y
    renderSplinePreview()
}

/** Dragging away from the tap grows the newest node's tangent handle, curving the curve live. */
internal fun DrawingSurfaceView.splineMove(event: MotionEvent) {
    val pointerIndex = event.findPointerIndex(gesturePointerId)
    if (pointerIndex < 0) return
    val box = layout.boxes.getOrNull(currentPage) ?: return
    val tx = box.toPtX(event.getX(pointerIndex), scrollX) - splineAnchorX
    val ty = box.toPtY(event.getY(pointerIndex), scrollY) - splineAnchorY
    splineNodes[splineNodes.lastIndex] = SplineNode(splineAnchorX, splineAnchorY, tx, ty)
    renderSplinePreview()
}

/** The node is placed; the curve stays open, waiting for the next tap (or the finishing double-tap). */
internal fun DrawingSurfaceView.splineUp(event: MotionEvent) {
    splineDragging = false
    gesturePointerId = -1
    stylusOwner = false
    // A drag isn't a tap, so it can't be half of the double-tap that finishes the curve.
    if (hypot(event.x - splineTapX, event.y - splineTapY) > doubleTapSlopPx) splineTapTime = 0L
    renderSplinePreview()
}

internal fun DrawingSurfaceView.pairsWithPreviousSplineTap(time: Long, x: Float, y: Float): Boolean =
    splineTapTime != 0L && time - splineTapTime <= doubleTapTimeoutMs &&
        hypot(x - splineTapX, y - splineTapY) <= doubleTapSlopPx

/** Show the curve-so-far as the in-progress stroke, so it paints exactly as it will commit. */
internal fun DrawingSurfaceView.renderSplinePreview() {
    current = ArrayList(SplineBuilder.build(splineNodes, shapeWidthPt))
    render()
}

/** True while a spline is open — the editor uses this to decide whether Enter/Esc apply. */
fun DrawingSurfaceView.splineInProgress(): Boolean = splineNodes.isNotEmpty()

/**
 * Commit the open spline as one ordinary stroke (the same shape any other tool produces) and
 * clear the tool's state. Safe to call when nothing is open; a one-node spline is just dropped.
 */
fun DrawingSurfaceView.finishSpline() {
    if (splineNodes.isEmpty()) return
    val pts = SplineBuilder.build(splineNodes, shapeWidthPt)
    clearSpline()
    if (pts.size >= 2) {
        appendStroke(
            currentPage,
            Stroke(
                tool, strokeColor(), "round", pts, true,
                lineStyle = currentLineStyle, fill = currentFill,
            ),
        )
    }
    finishGesture()
    render()
}

/** Throw the open spline away without committing it (Escape, or switching tools mid-curve). */
fun DrawingSurfaceView.cancelSpline() {
    if (splineNodes.isEmpty()) return
    clearSpline()
    gestureStartDoc = null
    render()
}

internal fun DrawingSurfaceView.clearSpline() {
    splineNodes.clear()
    splineDragging = false
    splineTapTime = 0L
    current = null
    gesturePointerId = -1
    stylusOwner = false
}

/** The eraser: touch/drag deletes any stroke it passes over on the page under the pointer. */
internal fun DrawingSurfaceView.startErase(event: MotionEvent, pointerIndex: Int) {
    scrolling = false
    erasing = true
    gestureStartDoc = doc
    gesturePointerId = event.getPointerId(pointerIndex)
    val box = layout.pageAt(event.getX(pointerIndex) + scrollX, event.getY(pointerIndex) + scrollY) ?: return
    currentPage = box.index
    // Eraser tip and barrel button both use WHOLE_STROKE mode to delete entire strokes.
    val isEraserTip = event.getToolType(pointerIndex) == android.view.MotionEvent.TOOL_TYPE_ERASER
    val isBarrelPressed = barrelPressed(event)
    val mode = if (isEraserTip || isBarrelPressed) EraserMode.WHOLE_STROKE else eraserMode
    eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex), mode)
}

internal fun DrawingSurfaceView.eraseMove(event: MotionEvent) {
    val pointerIndex = event.findPointerIndex(gesturePointerId)
    if (pointerIndex < 0) return
    val box = layout.boxes.getOrNull(currentPage) ?: return
    // Eraser tip and barrel button both use WHOLE_STROKE mode to delete entire strokes.
    val isEraserTip = event.getToolType(pointerIndex) == android.view.MotionEvent.TOOL_TYPE_ERASER
    val isBarrelPressed = barrelPressed(event)
    val mode = if (isEraserTip || isBarrelPressed) EraserMode.WHOLE_STROKE else eraserMode
    eraseAt(box, event.getX(pointerIndex), event.getY(pointerIndex), mode)
}

internal fun DrawingSurfaceView.eraseAt(box: PageBox, vx: Float, vy: Float, mode: EraserMode = eraserMode) {
    val px = box.toPtX(vx, scrollX)
    val py = box.toPtY(vy, scrollY)
    eraseX = vx; eraseY = vy
    eraseOnPage(currentPage, px, py, eraserRadiusPt, mode)
    render()
}

/** A tap in a placement tool: remember where it went down; a small drag cancels it. */
internal fun DrawingSurfaceView.beginPlace(event: MotionEvent, pointerIndex: Int) {
    scrolling = false
    erasing = false
    current = null
    placing = true
    placeDownX = event.getX(pointerIndex)
    placeDownY = event.getY(pointerIndex)
}

/** Moving past the tap slop turns a placement into a no-op (the user is scrubbing, not tapping). */
internal fun DrawingSurfaceView.placeMove(event: MotionEvent) {
    if (hypot(event.x - placeDownX, event.y - placeDownY) > DrawingSurfaceDefaults.TAP_SLOP_PX) placing = false
}

/** Fire [onPlace] for the page/point the tap landed on, hitting an existing text box if any. */
internal fun DrawingSurfaceView.commitPlace() {
    val kind = placeKind ?: return
    val box = layout.pageAt(placeDownX + scrollX, placeDownY + scrollY) ?: return
    val xPt = box.toPtX(placeDownX, scrollX)
    val yPt = box.toPtY(placeDownY, scrollY)
    val existing = if (kind == PlaceKind.TEXT) textEdits.pickForEditing(box.index, xPt, yPt) else null
    onPlace?.invoke(kind, Placement(box.index, xPt, yPt, existing))
}
/**
 * Append every sample for the gesture's pointer — historical first — as pressure-scaled
 * page-local points. Sampling only [pointerIndex] (not pointer 0) is what lets a resting palm
 * coexist with the pen: the palm is a different pointer and is never read here.
 */
internal fun DrawingSurfaceView.addSamples(event: MotionEvent, pointerIndex: Int, box: PageBox, into: MutableList<StrokePoint>) {
    for (h in 0 until event.historySize) {
        smoother.accept(
            event.getHistoricalX(pointerIndex, h),
            event.getHistoricalY(pointerIndex, h),
            event.getHistoricalPressure(pointerIndex, h),
        )?.let { into += point(box, it.x, it.y, it.pressure) }
    }
    // The batch's newest sample is always kept, so the drawn line reaches the pen.
    smoother.accept(
        event.getX(pointerIndex), event.getY(pointerIndex), event.getPressure(pointerIndex), force = true,
    )?.let { into += point(box, it.x, it.y, it.pressure) }
}

/**
 * The stroke width one sample of the current tool draws at, in pt. A highlighter lays down a
 * broad, constant-width band and ignores pressure; every other tool tapers with pressure. Shapes
 * and splines call this once per gesture so they match a pen stroke drawn at the same size.
 */
internal fun DrawingSurfaceView.widthForPressure(pressure: Float): Double = if (tool == Tool.HIGHLIGHTER) {
    (baseWidthPt * DrawingSurfaceDefaults.HIGHLIGHTER_WIDTH_FACTOR).toDouble()
} else {
    val p = if (pressure <= 0f) 1f else pressure
    (baseWidthPt * PressureCurve.factor(p, pressureGamma)).toDouble()
}

internal fun DrawingSurfaceView.point(box: PageBox, vx: Float, vy: Float, pressure: Float): StrokePoint {
    val width = widthForPressure(pressure)
    val (gx, gy) = guided(box, box.toPtX(vx, scrollX), box.toPtY(vy, scrollY))
    return StrokePoint(x = gx, y = gy, width = width)
}

internal fun DrawingSurfaceView.commitCurrent() {
    val raw = current ?: return
    current = null
    val wasShaping = shaping
    shaping = false
    // Shape tools emit exact geometry — only freehand samples get thinned.
    // Thin against the page's real px/pt (fit-to-width × zoom), not the zoom alone — on a large
    // screen those differ by 2–4×, and using the zoom leaves visible facets at 100% and below.
    val pxPerPt = layout.boxes.getOrNull(currentPage)?.scale ?: zoom
    var snapped = false
    val pts = if (wasShaping) {
        raw
    } else {
        val thinned = StrokeSimplifier.simplify(raw, StrokeSimplifier.toleranceFor(pxPerPt, strokePrecision))
        // With the recogniser on, a freehand stroke that clearly means a primitive is replaced by
        // clean geometry; anything it doesn't recognise comes through exactly as drawn.
        val shape = if (recognizeShapes && tool == Tool.PEN && thinned.isNotEmpty()) {
            // Keep the thickness the stroke was actually drawn at, not the un-scaled base width,
            // so snapping to a primitive doesn't fatten the line under the user's hand.
            ShapeRecognizer.recognize(thinned, thinned.map { it.width }.average())
        } else {
            null
        }
        snapped = shape != null
        shape ?: thinned
    }
    if (pts.size >= 2) {
        // Highlighter and geometric shapes are constant-width → store a single width; the freehand
        // pen keeps its per-vertex pressure. Live line-style/fill are baked in so they round-trip.
        val uniform = tool == Tool.HIGHLIGHTER || wasShaping || snapped
        val stroke = Stroke(
            tool, strokeColor(), "round", pts, uniform,
            lineStyle = currentLineStyle, fill = currentFill,
        )
        appendStroke(currentPage, stroke)
    }
    render()
}

/** Highlighter is stored semi-transparent so it round-trips (and renders) translucent. */
internal fun DrawingSurfaceView.strokeColor(): Int =
    if (tool == Tool.HIGHLIGHTER && (colorArgb ushr 24) == 0xFF) {
        colorArgb.withAlpha(XoppColor.HIGHLIGHTER_ALPHA)
    } else {
        colorArgb
    }

// --- audio: stamp strokes while recording, replay them on a play-object tap ------------------

/**
 * Report the recording behind the topmost stroke under a play-object tap. Reports null (rather
 * than staying silent) when the tap misses or lands on a stroke that was drawn without audio, so
 * the editor can say so instead of leaving the tap looking broken.
 */
internal fun DrawingSurfaceView.audioTap(event: MotionEvent, pointerIndex: Int) {
    val x = event.getX(pointerIndex)
    val y = event.getY(pointerIndex)
    val box = layout.pageAt(x + scrollX, y + scrollY) ?: return
    val xPt = box.toPtX(x, scrollX)
    val yPt = box.toPtY(y, scrollY)
    val page = doc.pages.getOrNull(box.index) ?: return
    val ref = SelectionTester.pickTopmost(page, xPt, yPt)
        ?.let { page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) }
        ?.let { it as? Stroke }
        ?.audioRef()
    onAudioTap?.invoke(ref)
}

/** [stroke] with the live recording position stamped on, or unchanged when nothing is recording. */
internal fun DrawingSurfaceView.withAudioStamp(stroke: Stroke): Stroke =
    audioStamp?.invoke()?.let(stroke::withAudio) ?: stroke

/** Append [stroke] to the active (or top) layer of page [pageIndex], rebuilding the model. */
internal fun DrawingSurfaceView.appendStroke(pageIndex: Int, stroke: Stroke) {
    val pages = doc.pages.toMutableList()
    val page = pages[pageIndex]
    val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
    val target = resolvedActiveLayer(page).coerceIn(0, layers.lastIndex)
    // Stamping here rather than at each commit site covers freehand, shapes and splines alike.
    layers[target] = Layer(layers[target].elements + withAudioStamp(stroke), layers[target].name)
    pages[pageIndex] = page.copy(layers = layers)
    doc = doc.copy(pages = pages)
    relayout() // rebuild boxes so they reference the updated pages, not stale ones
}

/** The layer new ink lands on for [page]: [activeLayerIndex] when in range, else the top layer. */
internal fun DrawingSurfaceView.resolvedActiveLayer(page: Page): Int =
    if (activeLayerIndex in page.layers.indices) activeLayerIndex else page.layers.lastIndex

/**
 * Apply the eraser disc to page [pageIndex] in the current [eraserMode], on the selected layer
 * only and skipping hidden layers ([PageEraser]). Returns true if anything on the page changed.
 */
internal fun DrawingSurfaceView.eraseOnPage(pageIndex: Int, px: Double, py: Double, radius: Double, mode: EraserMode = eraserMode): Boolean {
    val page = doc.pages.getOrNull(pageIndex) ?: return false
    val hidden = page.layers.indices.filter { isLayerHidden(pageIndex, it) }.toSet()
    val target = resolvedActiveLayer(page)
    val erased = PageEraser.erase(page, px, py, radius, mode, hidden, target) ?: return false
    val pages = doc.pages.toMutableList()
    pages[pageIndex] = erased
    doc = doc.copy(pages = pages)
    relayout()
    return true
}
