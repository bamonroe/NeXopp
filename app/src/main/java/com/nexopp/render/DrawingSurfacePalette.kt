/**
 * [DrawingSurfaceView]'s radial palette: the pen-tip hold that summons the ring, the confirming of a
 * two-finger tap, and the open menu itself — anchoring, hit tracking, haptics and commit. Extensions
 * on the view, so they read its state directly; the small amount of gesture state they need lives on
 * the view in `DrawingSurfaceView.kt`.
 *
 * A two-finger tap is confirmed here but not *interpreted* here: what it invokes is the user's
 * [TouchGestures] setting, applied by `DrawingSurfaceTaps.kt` — the palette is only one of the
 * things it can be bound to.
 */
package com.nexopp.render

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.nexopp.ui.RadialHit
import com.nexopp.ui.RadialPalette
import com.nexopp.ui.RadialPoint
import com.nexopp.ui.clampAnchor
import com.nexopp.ui.hitTest
import kotlin.math.hypot

// --- palette invocation gestures ---------------------------------------------------------------
// The stylus's button-free way of summoning the ring (see [PaletteInvocation]), live only when the
// user has picked it. Fingers reach the palette through the Touch settings instead — any tap
// gesture there can be bound to [TouchAction.RADIAL_PALETTE].

/** Arm the pen-tip hold that opens the palette, for a single stylus pointer coming down. */
internal fun DrawingSurfaceView.armPaletteLongPress(event: MotionEvent) {
    cancelPaletteLongPress()
    if (inputSettings.paletteInvocation != PaletteInvocation.PEN_TIP_LONG_PRESS) return
    if (event.pointerCount != 1) return
    val kind = pointerKindOf(event, 0)
    if (kind != PointerKind.STYLUS && kind != PointerKind.ERASER_TIP) return
    paletteLongPressArmed = true
    paletteLongPressX = event.x
    paletteLongPressY = event.y
    paletteTimer.postDelayed(paletteLongPressArm, longPressMs)
}

/** A tip that travels past slop before the timeout is writing — never steal that stroke. */
internal fun DrawingSurfaceView.trackPaletteLongPressMove(event: MotionEvent) {
    if (!paletteLongPressArmed) return
    if (hypot(event.x - paletteLongPressX, event.y - paletteLongPressY) > touchSlopPx) {
        cancelPaletteLongPress()
    }
}

/** Drop any pending pen-tip hold (moved, lifted, cancelled, or a second pointer arrived). */
internal fun DrawingSurfaceView.cancelPaletteLongPress() {
    if (!paletteLongPressArmed) return
    paletteLongPressArmed = false
    paletteTimer.removeCallbacks(paletteLongPressArm)
}

/**
 * The hold fired: throw away the few pixels of stroke the tip has laid down and put the ring up
 * where it rests. [DrawingSurfaceView.cancelGesture] rather than `endGesture` is what makes the ink
 * vanish instead of committing a dot to the document.
 */
internal fun DrawingSurfaceView.openPaletteOnLongPress() {
    paletteLongPressArmed = false
    cancelGesture()
    tick(HapticFeedbackConstants.LONG_PRESS)
    openPalette(palette, paletteLongPressX, paletteLongPressY)
}

/** A second finger landing starts a tap candidate; a third one ends any hope of a tap. */
internal fun DrawingSurfaceView.trackPaletteTapDown(event: MotionEvent) {
    if (!touchGestures.tracksTwoFingerTaps) return
    if (event.pointerCount != 2 || !bothFingers(event)) { twoFingerTap.cancel(); return }
    twoFingerTap.start(event.eventTime, event.getX(0), event.getY(0), event.getX(1), event.getY(1))
}

/** Feed both fingers to the detector, which drops the candidate once either one pans. */
internal fun DrawingSurfaceView.trackPaletteTapMove(event: MotionEvent) {
    if (event.pointerCount < 2) return
    twoFingerTap.move(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
}

/**
 * True when this lift completed a two-finger tap the user has bound something to — in which case the
 * pan it would otherwise have become is cancelled and that action runs midway between the fingers.
 * The run counter turns a second tap in the same place into the two-finger *double*-tap gesture.
 */
internal fun DrawingSurfaceView.handleTwoFingerTap(event: MotionEvent): Boolean {
    val (x, y) = twoFingerTap.release(event.eventTime) ?: return false
    return routeTwoFingerTap(twoFingerTaps.tap(event.eventTime, x, y), x, y)
}

/** True when every pointer down is a finger — a stylus in the mix is drawing, not summoning. */
private fun DrawingSurfaceView.bothFingers(event: MotionEvent): Boolean =
    (0 until event.pointerCount).all { pointerKindOf(event, it) == PointerKind.FINGER }

// --- the open menu -----------------------------------------------------------------------------

/**
 * Open [palette] anchored at ([x], [y]) in view pixels — where the gesture that summoned it was.
 * The anchor is clamped *here*, once, so a menu opened near an edge stays wholly on screen and
 * the hit test measures the flick from the same centre the ring is drawn at.
 */
fun DrawingSurfaceView.openPalette(palette: RadialPalette, x: Float, y: Float) {
    val overlay = RadialPaletteRenderer.Overlay(palette, x, y, presetColors = presetColors)
    val anchor =
        if (width > 0 && height > 0) {
            clampAnchor(x, y, width.toFloat(), height.toFloat(), overlay.geometry)
        } else {
            RadialPoint(x, y) // not laid out yet; the renderer clamps again at paint time
        }
    paletteOverlay = overlay.copy(anchorX = anchor.x, anchorY = anchor.y)
    lastPaletteAnchorX = anchor.x
    lastPaletteAnchorY = anchor.y
    render()
}

/**
 * Put [palette] up again at the anchor the menu that just closed used — what a
 * [com.nexopp.ui.PaletteAction.SwitchPalette] slot fires, so the pen never has to re-summon
 * the ring.
 */
fun DrawingSurfaceView.reopenPalette(palette: RadialPalette) =
    openPalette(palette, lastPaletteAnchorX, lastPaletteAnchorY)

/** Move the pen over the open menu, re-hit-testing which slot is highlighted. No-op if closed. */
fun DrawingSurfaceView.movePaletteTo(x: Float, y: Float) {
    val open = paletteOverlay ?: return
    val hit = open.palette.hitTest(open.anchorX, open.anchorY, x, y, open.geometry)
    if (hit != open.hit) {
        if (PaletteHaptics.shouldTick(open.hit, hit)) tick(HapticFeedbackConstants.CLOCK_TICK)
        paletteOverlay = open.copy(hit = hit)
        render()
    }
}

/** Fire one haptic [constant], unless the user has turned palette haptics off in settings. */
internal fun DrawingSurfaceView.tick(constant: Int) {
    if (paletteHaptics) performHapticFeedback(constant)
}

/** Close the menu and return what the pen was over — [RadialHit.Inert] if it wasn't open. */
fun DrawingSurfaceView.closePalette(): RadialHit {
    val open = paletteOverlay ?: return RadialHit.Inert
    paletteOverlay = null
    render()
    return open.hit
}

/**
 * Drive the open palette from a touch: dragging re-highlights, lifting fires the highlighted
 * slot. Returning true from `onTouchEvent` before any gesture begins is what guarantees the
 * menu never leaves a stroke behind it.
 */
internal fun DrawingSurfaceView.paletteTouch(event: MotionEvent): Boolean {
    // The fingers that summoned the menu are still on the glass; their lift is the end of the
    // *summoning* gesture, not a pick, so it is swallowed rather than committing a slot.
    if (palettePendingLift) {
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            palettePendingLift = false
        }
        return true
    }
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN ->
            movePaletteTo(event.x, event.y)
        MotionEvent.ACTION_UP -> { movePaletteTo(event.x, event.y); commitPalette() }
        MotionEvent.ACTION_CANCEL -> closePalette()
    }
    return true
}

/**
 * Run whatever the pen was over and **leave the menu up**, so several settings can be picked in
 * one summoning. Only an explicit "click off it" closes the ring: releasing clear of the menu
 * ([RadialHit.Outside]) — as does [alwaysClose], which the barrel button's eyes-free commit
 * passes. The hollow centre ([RadialHit.Inert]) is not a hit target: releasing there leaves the
 * menu exactly as it was.
 */
internal fun DrawingSurfaceView.commitPalette(alwaysClose: Boolean = false) {
    val hit = paletteOverlay?.hit ?: return
    if (hit is RadialHit.Inert) { if (alwaysClose) closePalette(); return }
    if (hit is RadialHit.Outside) { closePalette(); return }
    if (PaletteHaptics.shouldConfirm(hit)) tick(HapticFeedbackConstants.CONFIRM)
    if (alwaysClose || paletteCloseOnSelect) closePalette()
    val action = (hit as? RadialHit.Slot)?.action ?: return
    onPaletteAction?.invoke(action)
}
