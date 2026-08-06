/**
 * [DrawingSurfaceView]'s touch state machine: the routing of every pointer event (`handleTouch`,
 * `handleHover`, `handleGenericMotion` — called from the thin framework overrides that must stay on
 * the view), the pointer-kind/tool classification behind it, the two-finger pan and its release,
 * and the hand-tool double-tap. Extensions on the view, so they read its state directly.
 */
package com.xopp.android.render

import android.view.MotionEvent
import android.view.SurfaceView
import com.xopp.android.format.model.Tool
import kotlin.math.hypot

/**
 * The touch state machine, minus the framework override: null means "not ours", and the override
 * above hands those events on to [SurfaceView].
 */
internal fun DrawingSurfaceView.handleTouch(event: MotionEvent): Boolean? {
    // The open palette swallows the whole gesture before anything can begin — no stroke, no pan.
    if (paletteOpen) return paletteTouch(event)
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            momentum.stop(); beginPointer(event, 0); beginHandTap(event); armPageDrag(event)
            armPaletteLongPress(event)
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            handTapCandidate = false; cancelPageDrag(); cancelPaletteLongPress()
            trackPaletteTapDown(event); onPointerDown(event)
        }
        MotionEvent.ACTION_MOVE -> {
            if (handTapCandidate) trackHandTapMove(event)
            trackPageDragArm(event)
            trackPaletteLongPressMove(event)
            trackPaletteTapMove(event)
            // A lifted page owns the gesture outright — no panning, drawing or erasing underneath it.
            if (overview.dragging) { pageDragMove(event); return true }
            // The guide is dragged by its own finger and runs *alongside* the other gestures —
            // holding it steady while the pen rules along it is the whole point.
            if (guideDrag.dragging) guideDrag.move(event)
            when {
                scrolling -> doScroll(event)
                erasing -> eraseMove(event)
                placing -> placeMove(event)
                gestures.resizing -> gestures.resizeSelect(event)
                gestures.rotating -> gestures.rotateSelect(event)
                gestures.moving -> gestures.moveSelect(event)
                vspace.active -> vspace.move(event.y)
                gestures.banding -> gestures.bandMove(event)
                textSelecting -> textSelectMove(event)
                splineDragging -> splineMove(event)
                current != null -> extendStroke(event)
                else -> Unit
            }
        }
        // The two-finger tap is decided on the *first* lift — before the surviving finger can be
        // handed a pan by [onPointerUp] — and takes the whole gesture with it when it fires.
        MotionEvent.ACTION_POINTER_UP -> {
            if (openPaletteOnTwoFingerTap(event)) return true
            onPointerUp(event)
        }
        // A spline node is still mid-curve on release — it must not run the commit-and-finish path.
        MotionEvent.ACTION_UP -> when {
            overview.dragging -> { overview.disarm(); removeCallbacks(pageDragArm); finishPageDrag() }
            splineDragging -> splineUp(event)
            else -> {
                cancelPageDrag(); cancelPaletteLongPress(); paletteTap.cancel()
                captureReleaseVelocity(event); handleHandTapUp(event); endGesture()
            }
        }
        MotionEvent.ACTION_CANCEL -> {
            handTapCandidate = false; cancelPageDrag(); cancelPaletteLongPress()
            paletteTap.cancel(); cancelGesture()
        }
        else -> return null
    }
    return true
}

/** Barrel tracking plus the wheel; null means "not ours" and falls through to [SurfaceView]. */
internal fun DrawingSurfaceView.handleGenericMotion(event: MotionEvent): Boolean? {
    trackBarrelClicks(event)
    if (event.actionMasked == MotionEvent.ACTION_SCROLL && handleWheelScroll(event)) return true
    return null
}

/** The hover preview's state machine; null means "not ours" and falls through to [SurfaceView]. */
internal fun DrawingSurfaceView.handleHover(event: MotionEvent): Boolean? {
    trackBarrelClicks(event)
    // Hovering *is* the flick: the pen picks a slot without ever touching the glass. A hover exit
    // is not a cancel — it's what the tip coming down looks like, and the touch commits instead.
    if (paletteOpen) movePaletteTo(event.x, event.y)
    if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) { barrelClicks.reset(); barrelWasDown = false }
    val kind = pointerKindOf(event, 0)
    if (!showHover || (kind != PointerKind.STYLUS && kind != PointerKind.ERASER_TIP)) {
        if (hovering) { hovering = false; render() }
        return null
    }
    when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
            hovering = true; hoverX = event.x; hoverY = event.y; hoverKind = kind; render()
        }
        MotionEvent.ACTION_HOVER_EXIT -> { hovering = false; render() }
    }
    return true
}

/** Begin a gesture for the pointer at [pointerIndex], its intent decided by [InputClassifier]. */
internal fun DrawingSurfaceView.beginPointer(event: MotionEvent, pointerIndex: Int) {
    val kind = pointerKindOf(event, pointerIndex)
    // A finger laid on the guide manipulates it, whatever the active tool — the pen keeps
    // drawing against it meanwhile, exactly as you'd hold a real setsquare down and rule along it.
    if (kind == PointerKind.FINGER && guideDrag.begin(event, pointerIndex)) return
    // Play-object is a pure query — it never edits the document, so it short-circuits the whole
    // gesture machinery rather than earning a GestureIntent of its own.
    if (audioPlayMode) { audioTap(event, pointerIndex); return }
    val intent = InputClassifier.classify(kind, barrelPressed(event), activeTool(), inputSettings)
    stylusOwner = (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) &&
        (intent == GestureIntent.DRAW || intent == GestureIntent.ERASE)
    when (intent) {
        GestureIntent.PLACE -> beginPlace(event, pointerIndex)
        GestureIntent.PAN -> beginScroll(event)
        GestureIntent.SELECT -> beginSelect(event)
        GestureIntent.SELECT_TEXT -> beginTextSelect(event)
        GestureIntent.VERTICAL_SPACE -> beginVerticalSpace(event)
        GestureIntent.ERASE -> startErase(event, pointerIndex)
        // The spline tool is laid down over many taps, so it gets its own gesture path.
        GestureIntent.DRAW ->
            if (shapeKind == ShapeKind.SPLINE) splineDown(event, pointerIndex)
            else startStroke(event, pointerIndex)
        GestureIntent.IGNORE -> Unit
    }
}

/**
 * A second pointer arrived. A stylus/eraser tip takes over any in-progress finger gesture (so a
 * palm that landed first can't block the pen); while a stylus already owns the gesture, extra
 * finger/palm pointers are ignored (palm rejection); otherwise two fingers pan.
 */
internal fun DrawingSurfaceView.onPointerDown(event: MotionEvent) {
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
internal fun DrawingSurfaceView.onPointerUp(event: MotionEvent) {
    guideDrag.end(event)
    val upId = event.getPointerId(event.actionIndex)
    if (upId == gesturePointerId && (current != null || erasing)) {
        endGesture()
        return
    }
    lastFocusY = focusY(event, skip = event.actionIndex)
    lastFocusX = focusX(event, skip = event.actionIndex)
    // The span also jumps when a finger leaves; re-baseline it next frame so the drop isn't read as
    // a pinch-out, mirroring the focus/velocity reset below.
    lastSpan = 0f
    // The focus jumps when a finger leaves, so restart the estimate from the new baseline —
    // otherwise that discontinuity would read as a huge phantom flick. A side effect is that a
    // two-finger release carries no momentum (its near-motionless single-finger tail is all that
    // survives the reset), which is the intended feel — only a one-finger pan flings.
    momentum.rebaseline(event.eventTime, lastFocusX, lastFocusY)
}

/** Drop any in-progress draw/erase/place/band/move without committing (a stylus is taking over). */
internal fun DrawingSurfaceView.abandonInProgress() {
    clearSpline()
    current = null; shaping = false; erasing = false; placing = false
    scrolling = false; textSelecting = false; vspace.reset()
    gestures.reset()
}

internal fun DrawingSurfaceView.cancelGesture() {
    momentum.stop()
    guideDrag.end(null)
    clearSpline()
    current = null; shaping = false; scrolling = false; erasing = false; placing = false
    gestures.reset(); gestureStartDoc = null
    textSelecting = false; vspace.reset()
    gesturePointerId = -1; stylusOwner = false
}

/**
 * A mouse wheel notch scrolls the document vertically. Android reports wheel-down as a negative
 * [MotionEvent.AXIS_VSCROLL], so the sign flips: wheel down moves *further down* the document,
 * matching a scrollbar drag rather than a grab-the-paper pan. Zoom is untouched, and the move is
 * clamped by the same [ViewportState] bounds the pan gesture uses.
 */
internal fun DrawingSurfaceView.handleWheelScroll(event: MotionEvent): Boolean {
    val notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
    if (notches == 0f) return false
    val step = DrawingSurfaceDefaults.WHEEL_SCROLL_DP * resources.displayMetrics.density
    if (!scrollViewportBy(0f, -notches * step)) return false
    render()
    return true
}

/** Map one event pointer's tool type to our device-independent [PointerKind]. */
internal fun DrawingSurfaceView.pointerKindOf(event: MotionEvent, pointerIndex: Int): PointerKind =
    when (event.getToolType(pointerIndex)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        else -> PointerKind.UNKNOWN
    }

internal fun DrawingSurfaceView.barrelPressed(event: MotionEvent): Boolean =
    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

/** The on-screen tool collapsed to the classifier's [ActiveTool]. */
internal fun DrawingSurfaceView.activeTool(): ActiveTool = when {
    verticalSpaceMode -> ActiveTool.VERTICAL_SPACE
    placeKind != null -> ActiveTool.PLACE
    handMode -> ActiveTool.HAND
    textSelectMode -> ActiveTool.TEXT_SELECT
    selectMode -> ActiveTool.SELECT
    tool == Tool.ERASER -> ActiveTool.ERASER
    tool == Tool.HIGHLIGHTER -> ActiveTool.HIGHLIGHTER
    else -> ActiveTool.PEN
}

/** Feed one off-glass event's button state to [barrelClicks] and run the action it completes. */
internal fun DrawingSurfaceView.trackBarrelClicks(event: MotionEvent) {
    val down = barrelPressed(event)
    val edge = down && !barrelWasDown
    // Holding the barrel arms the eraser before the tip ever lands, so the hover preview has to
    // swap to the tip outline on the button edge — not on the first touch.
    if (down != barrelWasDown) { barrelWasDown = down; if (hovering) render() }
    if (!edge || !barrelClicks.press(event.eventTime)) return
    // A second double-click while the menu is up commits the highlighted slot — the eyes-free
    // way out for a pen that never comes down on the glass.
    if (paletteOpen) { commitPalette(alwaysClose = true); return }
    when (val action = inputSettings.barrelDoubleAction) {
        BarrelDoubleAction.NONE -> Unit
        BarrelDoubleAction.UNDO -> undo()
        BarrelDoubleAction.REDO -> redo()
        // The double-click setting is the sole owner of what the barrel button does: if it says
        // palette, the palette opens, whatever touch gesture is also bound in the settings.
        BarrelDoubleAction.RADIAL_PALETTE -> openPalette(palette, event.x, event.y)
        else -> onBarrelDoubleClick?.invoke(action)
    }
}

/** A second finger (or the Hand tool) started panning: abandon any partial stroke/erase/place. */
internal fun DrawingSurfaceView.beginScroll(event: MotionEvent) {
    current = null
    erasing = false
    placing = false
    scrolling = true
    lastFocusY = focusY(event, skip = -1)
    lastFocusX = focusX(event, skip = -1)
    lastSpan = spanOf(event)
    // A fresh pan starts with no carried flick — otherwise a near-motionless release could keep a
    // latched velocity from the previous gesture (see [captureReleaseVelocity]).
    momentum.clearRelease()
    momentum.rebaseline(event.eventTime, lastFocusX, lastFocusY)
}

internal fun DrawingSurfaceView.doScroll(event: MotionEvent) {
    val fy = focusY(event, skip = -1)
    val fx = focusX(event, skip = -1)
    // Pan gain: 1 tracks the finger one-to-one, <1 pans slower, >1 faster, 0 freezes the document.
    scrollY = (scrollY + (lastFocusY - fy) * panSensitivity).coerceIn(0f, maxScrollY())
    scrollX = (scrollX + (lastFocusX - fx) * panSensitivity).coerceIn(0f, maxScrollX())
    lastFocusY = fy
    lastFocusX = fx
    // Two fingers also pinch-zoom: a change in span since the last frame scales zoom about the focus.
    val span = spanOf(event)
    if (lastSpan > DrawingSurfaceDefaults.PINCH_MIN_SPAN_PX && span > DrawingSurfaceDefaults.PINCH_MIN_SPAN_PX) zoomAbout(fx, fy, span / lastSpan)
    lastSpan = span
    momentum.track(event.eventTime, fx, fy)
    render()
}

internal fun DrawingSurfaceView.endGesture() {
    guideDrag.end(null)
    // Snapshot then clear the mode flags first, so any render() inside a commit (e.g. the
    // rubber-band) sees the gesture already ended and doesn't paint a stale marquee/overlay.
    val wasScrolling = scrolling
    val wasErasing = erasing
    val wasPlacing = placing
    val wasMoving = gestures.moving
    val wasTransforming = gestures.resizing || gestures.rotating
    val wasBanding = gestures.banding
    val wasTextSelecting = textSelecting
    val wasVspacing = vspace.active
    vspace.reset()
    scrolling = false
    erasing = false
    placing = false
    textSelecting = false
    gestures.reset()
    when {
        wasPlacing -> commitPlace()
        wasMoving -> gestures.commitMove() // applied live; re-home if dropped on another page
        wasTransforming -> Unit  // resize/rotate applied live; finishGesture records them
        wasBanding -> gestures.commitBand()
        wasTextSelecting -> Unit // the word range is updated live; the selection just stays put
        // The shift is applied live and finishGesture records it as one undo step; the repaint is
        // what clears the grab-line overlay, which would otherwise stay drawn after the release.
        wasVspacing -> render()
        !wasScrolling && !wasErasing -> commitCurrent()
    }
    finishGesture()
    gesturePointerId = -1
    stylusOwner = false
    if (wasScrolling) momentum.launch(panSensitivity)
}

/** Record one undo step if this gesture actually changed the document. */
internal fun DrawingSurfaceView.finishGesture() {
    val start = gestureStartDoc ?: return
    gestureStartDoc = null
    if (doc !== start) {
        history.record(start)
        notifyHistory()
    }
}

/** Mean Y of all pointers except [skip] (an index being lifted), in view px. */
internal fun DrawingSurfaceView.focusY(event: MotionEvent, skip: Int): Float {
    var sum = 0f
    var n = 0
    for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getY(i); n++ }
    return if (n == 0) event.y else sum / n
}

/** Mean X of all pointers except [skip] (an index being lifted), in view px. */
internal fun DrawingSurfaceView.focusX(event: MotionEvent, skip: Int): Float {
    var sum = 0f
    var n = 0
    for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getX(i); n++ }
    return if (n == 0) event.x else sum / n
}

/** Mean distance of every pointer from the touch focus (view px); a pinch's "size". 0 for <2 pointers. */
internal fun DrawingSurfaceView.spanOf(event: MotionEvent): Float {
    if (event.pointerCount < 2) return 0f
    val fx = focusX(event, skip = -1)
    val fy = focusY(event, skip = -1)
    var sum = 0f
    for (i in 0 until event.pointerCount) sum += hypot(event.getX(i) - fx, event.getY(i) - fy)
    return sum / event.pointerCount
}

/** Arm double-tap tracking for a fresh single-finger touch, but only while the Hand tool is active. */
internal fun DrawingSurfaceView.beginHandTap(event: MotionEvent) {
    handTapCandidate = handMode
    handTapMoved = false
    handTapDownTime = event.eventTime
    handTapDownX = event.x
    handTapDownY = event.y
}

/** A moved-too-far touch is a pan, not a tap — disqualify it from forming a double-tap. */
internal fun DrawingSurfaceView.trackHandTapMove(event: MotionEvent) {
    if (hypot(event.x - handTapDownX, event.y - handTapDownY) > doubleTapSlopPx) handTapMoved = true
}

/** On lift, confirm a tap and — if it pairs with the previous one in time and place — fire the double-tap. */
internal fun DrawingSurfaceView.handleHandTapUp(event: MotionEvent) {
    if (!handTapCandidate) return
    handTapCandidate = false
    // A flick is a pan, not a tap — even one whose travel stayed inside the tap slop. Without this
    // a short-but-fast swipe in the overview grid would count as a tap and jump back to the page
    // under the finger, cancelling the glide it should have started.
    if (handTapMoved || momentum.hasRelease) { handFirstTapTime = 0L; return }
    // In the overview grid a tap is about pages, not paging/zooming around: in edit mode it picks
    // pages out for a bulk edit, in view mode it just jumps to the page you tapped.
    if (columns > 1) {
        handFirstTapTime = 0L
        val box = layout.pageAt(scrollX + handTapDownX, scrollY + handTapDownY) ?: return
        if (overview.editMode) overview.toggleSelection(box.index, doc.pages.size) else goToPage(box.index)
        return
    }
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
internal fun DrawingSurfaceView.onHandDoubleTap(x: Float) {
    val edge = width / 3f
    when {
        x < edge -> goToPage(currentPageIndex() - 1)
        x > width - edge -> goToPage(currentPageIndex() + 1)
        else -> onToggleFullPage?.invoke()
    }
}
