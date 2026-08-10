package com.nexopp.render

/**
 * Physical source of a pointer — a device-independent mirror of `MotionEvent.getToolType`, so the
 * classifier below stays a pure JVM value function (the view maps the Android constants onto these).
 */
enum class PointerKind {
    /** A finger / touch contact (`TOOL_TYPE_FINGER`), or a resting palm. */
    FINGER,

    /** The pen tip of a stylus (`TOOL_TYPE_STYLUS`). */
    STYLUS,

    /** The "eraser" end of a stylus flipped over (`TOOL_TYPE_ERASER`). */
    ERASER_TIP,

    /** A mouse / trackpad / anything else — treated as a drawing pointer, never as a palm. */
    UNKNOWN,
}

/**
 * The user's currently selected on-screen tool, collapsed from the rail's `EditorTool` (Hand and the
 * authoring tools reduce to [HAND] / [PLACE] here — the classifier only cares about intent, not which
 * placement kind).
 */
enum class ActiveTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    SELECT,
    BACKGROUND_SELECT,
    TEXT_SELECT,
    HAND,
    PLACE,

    /** Drag a horizontal line to insert/remove vertical space on a page. */
    VERTICAL_SPACE;

    /** The three tools whose primary gesture is laying ink down (subject to the finger-draw gate). */
    fun isDrawing(): Boolean = this == PEN || this == HIGHLIGHTER || this == ERASER

    /** What a plain pointer of this tool does, absent any stylus override or finger-draw gate. */
    fun defaultIntent(): GestureIntent = when (this) {
        PEN, HIGHLIGHTER -> GestureIntent.DRAW
        ERASER -> GestureIntent.ERASE
        SELECT -> GestureIntent.SELECT
        BACKGROUND_SELECT -> GestureIntent.BACKGROUND_SELECT
        TEXT_SELECT -> GestureIntent.SELECT_TEXT
        HAND -> GestureIntent.PAN
        PLACE -> GestureIntent.PLACE
        VERTICAL_SPACE -> GestureIntent.VERTICAL_SPACE
    }
}

/** What the stylus barrel (primary side-button) does when held during a stroke — a user setting. */
enum class BarrelAction {
    /** Ignore the button; the on-screen tool applies as normal. */
    NONE,

    /** Erase while held, regardless of the on-screen tool (the default, matching desktop). */
    ERASE,

    /** Rubber-band select while held. */
    SELECT,
}

/**
 * What a *rapid double-click* of the stylus barrel button invokes — a user setting distinct from the
 * held [BarrelAction]. The two never collide: the held action is decided at pointer-down (the tip is
 * on the glass), while a double-click is a button-only gesture recognised by [BarrelClickDetector].
 */
enum class BarrelDoubleAction {
    /** Ignore double-clicks entirely. */
    NONE,

    /** Undo the last edit (the default — the most-wanted eyes-free action). */
    UNDO,

    /** Redo the last undone edit. */
    REDO,

    /** Flip between the eraser and the previous drawing tool. */
    TOGGLE_ERASER,

    /** Flip between the Select tool and the previous drawing tool. */
    TOGGLE_SELECT,

    /** Show/hide the chrome (full-page view). */
    TOGGLE_FULL_PAGE,

    /** Open the radial palette at the pen tip; a flick onto a slot fires that slot's action. */
    RADIAL_PALETTE,
}

/**
 * Recognises a double *click* of the barrel button from the stream of press edges. Pure and
 * time-injected (callers pass `MotionEvent.eventTime`) so it is fully unit-testable on the JVM.
 *
 * A click pair counts only when the second press lands within [windowMs] of the first; once it
 * fires, the state resets so a third press starts a fresh pair rather than triggering again.
 */
class BarrelClickDetector(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastPressMs = Long.MIN_VALUE

    /** Feed one button-press edge at [timeMs]; returns true when it completes a double-click. */
    fun press(timeMs: Long): Boolean {
        val doubled = lastPressMs != Long.MIN_VALUE && timeMs - lastPressMs in 0..windowMs
        lastPressMs = if (doubled) Long.MIN_VALUE else timeMs
        return doubled
    }

    /** Forget any pending first click (e.g. the stylus left hover range). */
    fun reset() { lastPressMs = Long.MIN_VALUE }

    companion object {
        /** Matches Android's own multi-tap window closely enough to feel native. */
        const val DEFAULT_WINDOW_MS = 350L
    }
}

/** The gesture a pointer-down should begin. */
enum class GestureIntent {
    DRAW,
    ERASE,
    PAN,
    SELECT,
    BACKGROUND_SELECT,
    SELECT_TEXT,
    PLACE,
    VERTICAL_SPACE,
    IGNORE,
}

/** Input-layer preferences the classifier consults (owned by the app's settings). */
data class InputSettings(
    /** When false, finger pointers only pan/zoom — they actuate no tool at all (stylus-only mode). */
    val fingerDraws: Boolean = true,
    /** What the stylus primary barrel-button invokes while held. */
    val barrelAction: BarrelAction = BarrelAction.ERASE,
    /** What a rapid double-click of that same button invokes. */
    val barrelDoubleAction: BarrelDoubleAction = BarrelDoubleAction.UNDO,
    /** Which *touch* gesture summons the radial palette (see [PaletteInvocation]). */
    val paletteInvocation: PaletteInvocation = PaletteInvocation.NONE,
)

/**
 * Which **touch** gesture summons the radial palette. The barrel button is deliberately absent here:
 * a double-click of it is owned solely by [BarrelDoubleAction.RADIAL_PALETTE], so there is exactly
 * one owner of that gesture. This setting exists for the many styluses with no side button at all —
 * those users need a gesture the tip or the fingers can make. Exactly one is live at a time: a
 * long-press that also opened on a two-finger tap would fight the pan/zoom gestures for every touch.
 */
enum class PaletteInvocation(val label: String) {
    /** The default: no touch gesture opens the palette (the barrel double-click still can). */
    NONE("None"),

    /** Hold the pen tip still on the glass; the ring opens where it rests, and no stroke is left. */
    PEN_TIP_LONG_PRESS("Pen-tip long press"),

    /** A quick two-finger tap that never becomes a pan or a pinch, opening midway between them. */
    TWO_FINGER_TAP("Two-finger tap"),
}

/**
 * Pure decision function: given a pointer's physical [PointerKind], whether the stylus barrel is
 * held, the on-screen [ActiveTool], and the user's [InputSettings], return the [GestureIntent] the
 * pointer should begin. Kept free of Android types so it is fully unit-testable on the JVM (see
 * `InputClassifierTest`); the stateful parts of stylus handling (palm rejection while a stylus is
 * already down, hover) live in `DrawingSurfaceView`, which routes every pointer-down through here.
 *
 * Precedence, matching desktop Xournal++'s "the pen hardware wins over the toolbar":
 *  1. The flipped-over **eraser tip** always erases, whatever the tool.
 *  2. A held **barrel button** applies its configured action (erase/select), whatever the tool.
 *  3. With **finger-draw off**, a finger only ever pans — it actuates no tool at all (pen, eraser,
 *     highlighter, text, selection, placement…), so a palm can't ink and only the stylus works.
 *  4. Otherwise the on-screen tool's [ActiveTool.defaultIntent].
 */
object InputClassifier {

    fun classify(
        kind: PointerKind,
        barrelPressed: Boolean,
        activeTool: ActiveTool,
        settings: InputSettings,
    ): GestureIntent {
        if (kind == PointerKind.ERASER_TIP) return GestureIntent.ERASE

        if (kind == PointerKind.STYLUS && barrelPressed) {
            when (settings.barrelAction) {
                BarrelAction.ERASE -> return GestureIntent.ERASE
                BarrelAction.SELECT -> return GestureIntent.SELECT
                BarrelAction.NONE -> Unit // fall through to the on-screen tool
            }
        }

        if (kind == PointerKind.FINGER && !settings.fingerDraws) {
            return GestureIntent.PAN
        }

        return activeTool.defaultIntent()
    }
}
