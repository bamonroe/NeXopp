package com.nexopp.render

import kotlin.math.hypot

/**
 * What a finger gesture on the canvas invokes — the touch counterpart of [BarrelDoubleAction].
 *
 * Every entry is an action the toolbar or the radial palette can already reach; the gesture is just
 * a second way in, so nothing here can drift into behaviour the rest of the app doesn't have. The
 * surface applies most of them itself; the ones that need the editor's own state (tool flips, the
 * chrome) are handed out through [DrawingSurfaceView.onTouchAction].
 */
enum class TouchAction(val label: String) {
    /** Ignore the gesture entirely. */
    NONE("Nothing"),

    /**
     * Split the screen in thirds by the tap's x: the left third pages back, the right third pages
     * forward, and the centre toggles full-page view. The historic meaning of a Hand-tool
     * double-tap, kept as a choice so upgrading changes nothing.
     */
    PAGE_ZONES("Page zones (edges turn, centre full-page)"),

    /** Show/hide the chrome (full-page view). */
    TOGGLE_FULL_PAGE("Toggle full page"),

    /** Undo the last edit. */
    UNDO("Undo"),

    /** Redo the last undone edit. */
    REDO("Redo"),

    /** Go to the next page. */
    NEXT_PAGE("Next page"),

    /** Go to the previous page. */
    PREVIOUS_PAGE("Previous page"),

    /** Open the radial palette where the gesture landed; a flick onto a slot fires that slot. */
    RADIAL_PALETTE("Open the radial palette"),

    /** Flip between the eraser and the previous drawing tool. */
    TOGGLE_ERASER("Toggle eraser"),

    /** Flip between the Select tool and the previous drawing tool. */
    TOGGLE_SELECT("Toggle select"),

    /** Flip between the Hand (pan) tool and the previous drawing tool. */
    TOGGLE_HAND("Toggle hand"),
}

/**
 * What each finger tap gesture does, plus how long a run of taps may take — the Touch settings
 * section, as the one value the surface consults.
 *
 * A one-finger tap is only ever read as a gesture when the finger *isn't* drawing (the Hand tool, or
 * finger-draw switched off); two-finger gestures are read whatever the tool, because a second finger
 * always means pan/zoom rather than ink.
 *
 * @property doubleTap What two quick one-finger taps invoke.
 * @property tripleTap What three quick one-finger taps invoke. Set to anything but [TouchAction.NONE]
 *   and the double-tap has to wait out [tapWindowMs] before firing, in case a third tap is coming.
 * @property twoFingerTap What a quick two-finger tap invokes.
 * @property twoFingerDoubleTap What two quick two-finger taps invoke — likewise delaying the single.
 * @property tapWindowMs How long a run of taps may take, in ms; [TapWindow.SYSTEM] follows Android's
 *   own double-tap timeout.
 */
data class TouchGestures(
    val doubleTap: TouchAction = TouchAction.PAGE_ZONES,
    val tripleTap: TouchAction = TouchAction.NONE,
    val twoFingerTap: TouchAction = TouchAction.NONE,
    val twoFingerDoubleTap: TouchAction = TouchAction.NONE,
    val tapWindowMs: Int = TapWindow.SYSTEM,
) {
    /** True when some two-finger gesture is configured — otherwise the candidate is never tracked. */
    val tracksTwoFingerTaps: Boolean
        get() = twoFingerTap != TouchAction.NONE || twoFingerDoubleTap != TouchAction.NONE

    /** True when some one-finger multi-tap is configured. */
    val tracksSingleFingerTaps: Boolean
        get() = doubleTap != TouchAction.NONE || tripleTap != TouchAction.NONE
}

/** The tap-run window: a fixed number of milliseconds, or Android's own double-tap timeout. */
object TapWindow {
    /** Follow `ViewConfiguration.getDoubleTapTimeout()` — the default, and what the app always used. */
    const val SYSTEM = 0

    /** The windows the Touch section offers, in ms ([SYSTEM] first). */
    val CHOICES: List<Int> = listOf(SYSTEM, 200, 300, 400, 500, 650, 800)

    /** How a choice reads in the settings row. */
    fun label(ms: Int): String = if (ms <= SYSTEM) "System default" else "$ms ms"

    /** The window to actually measure against, given the platform's own timeout. */
    fun resolve(ms: Int, systemMs: Long): Long = if (ms <= SYSTEM) systemMs else ms.toLong()
}

/**
 * Counts a run of taps in the same place: the pure state machine behind the double- and triple-tap
 * gestures, free of Android types so every rule is unit-testable on the JVM (`MultiTapDetectorTest`).
 *
 * Callers feed it taps they have *already* confirmed as taps (down→up without a pan), one finger's
 * or two fingers' midpoint; it only decides whether each one continues the previous run or starts a
 * fresh one. A tap continues the run when it lands within [windowMs] of the last one and no further
 * than [slopPx] from it — the same two rules Android's own double-tap uses.
 *
 * The run wraps at [maxTaps] rather than counting on forever, so the tap after a triple-tap begins a
 * new run instead of reporting a fourth.
 */
class MultiTapDetector(
    private val slopPx: Float,
    var windowMs: Long,
    private val maxTaps: Int = 3,
) {

    private var count = 0
    private var lastTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** Feed one confirmed tap at [time] (event-time millis); returns its place in the run, from 1. */
    fun tap(time: Long, x: Float, y: Float): Int {
        val continues = count > 0 &&
            time - lastTime in 0..windowMs &&
            hypot(x - lastX, y - lastY) <= slopPx
        count = if (continues) count + 1 else 1
        lastTime = time
        lastX = x
        lastY = y
        val place = count
        // The run has nowhere left to go: reset now so the next tap opens a fresh one.
        if (place >= maxTaps) reset()
        return place
    }

    /** Forget the run in progress — a pan, a cancelled gesture, a jump to another page. */
    fun reset() {
        count = 0
    }
}
