/**
 * [DrawingSurfaceView]'s finger-tap gestures: confirming a one-finger tap, counting a run of taps
 * into a double or a triple, and running the [TouchAction] the user has bound to each. The counting
 * rules themselves are [MultiTapDetector]'s (pure, and tested on the JVM); this file is only the
 * wiring between them and the surface.
 *
 * Two-finger taps are confirmed elsewhere — [TwoFingerTapDetector], driven from
 * `DrawingSurfacePalette.kt` — but they are *routed* here, so every tap gesture ends up in one
 * [applyTouchAction].
 */
package com.nexopp.render

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.hypot

// --- confirming a one-finger tap ---------------------------------------------------------------

/**
 * Arm tap tracking for a fresh single-finger touch, but only when that touch can't be drawing:
 * the Hand tool pans with any pointer, and with finger-draw switched off a finger only ever pans.
 * Anywhere else the touch is ink, and stealing it for a gesture would eat the stroke.
 */
internal fun DrawingSurfaceView.beginHandTap(event: MotionEvent) {
    handTapCandidate = handMode ||
        (!inputSettings.fingerDraws && pointerKindOf(event, 0) == PointerKind.FINGER)
    handTapMoved = false
    handTapDownTime = event.eventTime
    handTapDownX = event.x
    handTapDownY = event.y
}

/** A moved-too-far touch is a pan, not a tap — disqualify it from continuing the run. */
internal fun DrawingSurfaceView.trackHandTapMove(event: MotionEvent) {
    if (hypot(event.x - handTapDownX, event.y - handTapDownY) <= doubleTapSlopPx) return
    handTapMoved = true
    // Whatever is waiting on "is a further tap coming?" has its answer: this gesture is a pan, so
    // the deferred action must not land in the middle of it.
    cancelPendingTap()
}

/** On lift, confirm a tap and hand it to the run counter, which says what it completes. */
internal fun DrawingSurfaceView.handleHandTapUp(event: MotionEvent) {
    if (!handTapCandidate) return
    handTapCandidate = false
    // A flick is a pan, not a tap — even one whose travel stayed inside the tap slop. Without this
    // a short-but-fast swipe in the overview grid would count as a tap and jump back to the page
    // under the finger, cancelling the glide it should have started.
    if (handTapMoved || momentum.hasRelease) { handTaps.reset(); return }
    // In the overview grid a tap is about pages, not paging/zooming around: in edit mode it picks
    // pages out for a bulk edit, in view mode it just jumps to the page you tapped.
    if (columns > 1) {
        handTaps.reset()
        val box = layout.pageAt(scrollX + handTapDownX, scrollY + handTapDownY) ?: return
        if (overview.editMode) overview.toggleSelection(box.index, doc.pages.size) else goToPage(box.index)
        return
    }
    routeSingleFingerTap(
        count = handTaps.tap(handTapDownTime, handTapDownX, handTapDownY),
        x = handTapDownX,
        y = handTapDownY,
    )
}

// --- routing a run of taps to its action --------------------------------------------------------

/**
 * Run the gesture a one-finger run of [count] taps completes, if any.
 *
 * A double-tap can only fire immediately when no triple-tap is bound: otherwise every triple-tap
 * would fire the double's action on the way past, so the double waits out the tap window first and
 * the third tap cancels it.
 */
internal fun DrawingSurfaceView.routeSingleFingerTap(count: Int, x: Float, y: Float) {
    val gestures = touchGestures
    val action = when (count) {
        2 -> gestures.doubleTap
        3 -> gestures.tripleTap
        else -> return
    }
    if (action == TouchAction.NONE) return
    if (count == 3) {
        cancelPendingTap()
        applyTouchAction(action, x, y)
    } else if (gestures.tripleTap == TouchAction.NONE) {
        applyTouchAction(action, x, y)
    } else {
        deferTouchAction(action, x, y)
    }
}

/**
 * Run the gesture a two-finger run of [count] taps completes; true when one is bound and the touch
 * has been consumed, so the caller cancels the pan it would otherwise have become.
 *
 * The single/double relationship mirrors [routeSingleFingerTap]: a bound two-finger double-tap makes
 * the single one wait, and returning false leaves a gesture nobody has bound to run as an ordinary
 * (harmless) two-finger touch.
 */
internal fun DrawingSurfaceView.routeTwoFingerTap(count: Int, x: Float, y: Float): Boolean {
    val gestures = touchGestures
    val action = when (count) {
        1 -> gestures.twoFingerTap
        2 -> gestures.twoFingerDoubleTap
        else -> return false
    }
    if (action == TouchAction.NONE) return false
    cancelPageDrag()
    cancelGesture()
    handTapCandidate = false
    handTaps.reset()
    if (count == 2) cancelPendingTap()
    if (count == 1 && gestures.twoFingerDoubleTap != TouchAction.NONE) {
        // Deferred: by the time it fires the fingers are long gone, so there is no lift to swallow.
        deferTouchAction(action, x, y)
        return true
    }
    applyTouchAction(action, x, y)
    // Only a menu that just went up has fingers still resting on it: their lift ends the *summoning*
    // gesture rather than picking a slot, and this is what tells the open menu to swallow it. Set
    // from what actually happened, so an action that opened nothing can't leave the flag armed for
    // some later summoning to trip over.
    palettePendingLift = paletteOpen
    return true
}

/**
 * Hold [action] back for one tap window, in case the tap that would upgrade this run arrives. It
 * rides [DrawingSurfaceView.paletteTimer] — a plain main-looper handler — for the same reason the
 * pen-tip hold does: `View.postDelayed` sits on its hands until the view is attached to a window.
 */
internal fun DrawingSurfaceView.deferTouchAction(action: TouchAction, x: Float, y: Float) {
    cancelPendingTap()
    pendingTapAction = action
    pendingTapX = x
    pendingTapY = y
    paletteTimer.postDelayed(pendingTapFire, tapWindowMs())
}

/** Drop a deferred action — the upgrading tap arrived, or the gesture turned into a pan. */
internal fun DrawingSurfaceView.cancelPendingTap() {
    if (pendingTapAction == TouchAction.NONE) return
    pendingTapAction = TouchAction.NONE
    paletteTimer.removeCallbacks(pendingTapFire)
}

/** The tap-run window in force: the user's chosen span, or the platform's own double-tap timeout. */
internal fun DrawingSurfaceView.tapWindowMs(): Long =
    TapWindow.resolve(touchGestures.tapWindowMs, doubleTapTimeoutMs)

// --- doing the thing --------------------------------------------------------------------------

/**
 * Apply one [TouchAction] at the point ([x], [y]) the gesture landed on.
 *
 * Everything the surface owns outright is done here; the flips that need the editor's own state —
 * the chrome, which tool is selected — go out through [DrawingSurfaceView.onTouchAction], exactly as
 * the barrel double-click's do.
 */
internal fun DrawingSurfaceView.applyTouchAction(action: TouchAction, x: Float, y: Float) {
    when (action) {
        TouchAction.NONE -> Unit
        TouchAction.PAGE_ZONES -> applyPageZones(x)
        TouchAction.UNDO -> undo()
        TouchAction.REDO -> redo()
        TouchAction.NEXT_PAGE -> goToNextPage()
        TouchAction.PREVIOUS_PAGE -> goToPreviousPage()
        TouchAction.RADIAL_PALETTE -> {
            cancelGesture()
            tick(HapticFeedbackConstants.LONG_PRESS)
            openPalette(palette, x, y)
        }
        TouchAction.TOGGLE_FULL_PAGE -> onToggleFullPage?.invoke()
        TouchAction.TOGGLE_ERASER, TouchAction.TOGGLE_SELECT, TouchAction.TOGGLE_HAND ->
            onTouchAction?.invoke(action)
    }
}

/** Route by horizontal zone: left third → previous page, right third → next, centre → full page. */
internal fun DrawingSurfaceView.applyPageZones(x: Float) {
    val edge = width / 3f
    when {
        x < edge -> goToPage(currentPageIndex() - 1)
        x > width - edge -> goToPage(currentPageIndex() + 1)
        else -> onToggleFullPage?.invoke()
    }
}
