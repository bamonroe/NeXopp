package com.xopp.android.render

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
    HAND,
    PLACE;

    /** The three tools whose primary gesture is laying ink down (subject to the finger-draw gate). */
    fun isDrawing(): Boolean = this == PEN || this == HIGHLIGHTER || this == ERASER

    /** What a plain pointer of this tool does, absent any stylus override or finger-draw gate. */
    fun defaultIntent(): GestureIntent = when (this) {
        PEN, HIGHLIGHTER -> GestureIntent.DRAW
        ERASER -> GestureIntent.ERASE
        SELECT -> GestureIntent.SELECT
        HAND -> GestureIntent.PAN
        PLACE -> GestureIntent.PLACE
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

/** The gesture a pointer-down should begin. */
enum class GestureIntent { DRAW, ERASE, PAN, SELECT, PLACE, IGNORE }

/** Input-layer preferences the classifier consults (owned by the app's settings). */
data class InputSettings(
    /** When false, finger pointers only pan/zoom — never draw/erase (palm-safe for non-stylus use). */
    val fingerDraws: Boolean = true,
    /** What the stylus primary barrel-button invokes while held. */
    val barrelAction: BarrelAction = BarrelAction.ERASE,
)

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
 *  3. With **finger-draw off**, a finger on a drawing tool only pans (so a palm can't ink).
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

        if (kind == PointerKind.FINGER && !settings.fingerDraws && activeTool.isDrawing()) {
            return GestureIntent.PAN
        }

        return activeTool.defaultIntent()
    }
}
