package com.xopp.android.ui

import android.content.Context
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.Momentum
import com.xopp.android.render.MomentumCurve
import com.xopp.android.render.PanSensitivity
import com.xopp.android.render.PressureSensitivity
import com.xopp.android.render.StrokePrecision

/** Which edge of the editor the tool rail is docked to. */
enum class ToolbarPosition(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
    LEFT("Left"),
    RIGHT("Right"),
    ;

    /** True when the rail runs along a horizontal edge (top/bottom) and so lays its buttons in a row. */
    val isHorizontal: Boolean get() = this == TOP || this == BOTTOM
}

/**
 * The app's user-adjustable preferences (the Settings screen edits these; [SettingsStore] persists
 * them). Kept small and serialisable to `SharedPreferences` — these are input-layer behaviours only
 * and never touch the `.xopp` format.
 */
data class AppSettings(
    /** When false, fingers only pan/zoom — never draw (palm-safe for stylus users). */
    val fingerDraws: Boolean = true,
    /** What the stylus primary barrel-button does while held. */
    val barrelAction: BarrelAction = BarrelAction.ERASE,
    /** Show a preview ring where a hovering stylus will land. */
    val showHover: Boolean = true,
    /** How pen pressure maps to stroke width. */
    val sensitivity: PressureSensitivity = PressureSensitivity.LINEAR,
    /** How much digitiser detail a freehand stroke keeps (fidelity vs. file size). */
    val strokePrecision: StrokePrecision = StrokePrecision.DEFAULT,
    /** Snap a finished freehand stroke to the primitive it resembles (line, circle, rectangle…). */
    val recognizeShapes: Boolean = false,
    /** Pull shape-tool endpoints onto the page background's ruling (grid/lined sheets only). */
    val snapToGrid: Boolean = false,
    /** Pull a selection's rotate handle onto 15-degree increments. */
    val snapRotation: Boolean = false,
    /** The three user-configurable pen-tip widths (pt) behind the S/M/L size slots, in slot order. */
    val penWidths: List<Float> = DEFAULT_PEN_WIDTHS,
    /** The user-defined colour (opaque ARGB) behind the palette's editable custom slot. */
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    /** Which tool is active when a document first opens. */
    val defaultTool: EditorTool = EditorTool.PEN,
    /** How far a released pan keeps gliding — the momentum-strength factor (0 = off, 1 = normal). */
    val momentum: Float = Momentum.NORMAL,
    /** The velocity→coast response shape for momentum (linear … exponential). */
    val momentumCurve: MomentumCurve = MomentumCurve.QUADRATIC,
    /** How far the document moves per unit of pan travel (0 = frozen, 1 = one-to-one, >1 = faster). */
    val panSensitivity: Float = PanSensitivity.NORMAL,
    /** Which edge of the editor the tool rail is docked to. */
    val toolbarPosition: ToolbarPosition = ToolbarPosition.LEFT,
    /** Colours picked recently, most-recent-first, capped at [MAX_RECENT_COLORS]. */
    val recentColors: List<Int> = emptyList(),
    /** The pen colour in use when the app last ran, restored on the next launch. */
    val lastColor: Int = DEFAULT_LAST_COLOR,
    /** The pen width (pt) in use when the app last ran, restored on the next launch. */
    val lastWidth: Float = DEFAULT_PEN_WIDTHS[1],
    /**
     * Which tool each grouped rail slot currently stands for, keyed by [ToolGroup.id]. Missing
     * entries fall back to the group's first tool (see [selected]).
     */
    val toolGroupSelections: Map<String, EditorTool> = emptyMap(),
    /**
     * The rail's button positions in display order, by [RailItem.id]. Empty means the factory order;
     * ids the list omits are appended in factory order (see [orderedRailItems]).
     */
    val railOrder: List<String> = emptyList(),
    /** The [RailItem.id]s the user has hidden from the rail. Empty means everything is shown. */
    val railHidden: Set<String> = emptySet(),
) {
    /**
     * This settings object with [color] pushed to the front of [recentColors] — de-duplicated and
     * truncated to [MAX_RECENT_COLORS] — and recorded as the pen's [lastColor].
     */
    fun withColorUsed(color: Int): AppSettings = copy(
        recentColors = (listOf(color) + recentColors.filter { it != color }).take(MAX_RECENT_COLORS),
        lastColor = color,
    )

    companion object {
        /** Factory defaults for the three pen-width slots — the old fixed S/M/L values. */
        val DEFAULT_PEN_WIDTHS: List<Float> = listOf(0.85f, 1.5f, 2.6f)

        /** Factory default for the custom colour slot — a violet not already in the fixed palette. */
        val DEFAULT_CUSTOM_COLOR: Int = 0xFF9C27B0.toInt()

        /** Factory default pen colour (black) — the first entry of the fixed palette. */
        val DEFAULT_LAST_COLOR: Int = 0xFF000000.toInt()

        /** How many recently-used colours the picker remembers — one row's worth. */
        const val MAX_RECENT_COLORS: Int = 7
    }
}

/** Reads/writes [AppSettings] to a small `SharedPreferences` file. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("xopp_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            fingerDraws = prefs.getBoolean(KEY_FINGER_DRAWS, d.fingerDraws),
            barrelAction = enumOr(prefs.getString(KEY_BARREL, null), d.barrelAction),
            showHover = prefs.getBoolean(KEY_HOVER, d.showHover),
            sensitivity = enumOr(prefs.getString(KEY_SENSITIVITY, null), d.sensitivity),
            strokePrecision = enumOr(prefs.getString(KEY_STROKE_PRECISION, null), d.strokePrecision),
            recognizeShapes = prefs.getBoolean(KEY_RECOGNIZE_SHAPES, d.recognizeShapes),
            snapToGrid = prefs.getBoolean(KEY_SNAP_GRID, d.snapToGrid),
            snapRotation = prefs.getBoolean(KEY_SNAP_ROTATION, d.snapRotation),
            penWidths = d.penWidths.mapIndexed { i, w -> prefs.getFloat(keyPenWidth(i), w) },
            customColor = prefs.getInt(KEY_CUSTOM_COLOR, d.customColor),
            defaultTool = enumOr(prefs.getString(KEY_DEFAULT_TOOL, null), d.defaultTool),
            momentum = Momentum.coerce(prefs.getFloat(KEY_MOMENTUM, d.momentum)),
            momentumCurve = enumOr(prefs.getString(KEY_MOMENTUM_CURVE, null), d.momentumCurve),
            panSensitivity = PanSensitivity.coerce(prefs.getFloat(KEY_PAN_SENSITIVITY, d.panSensitivity)),
            toolbarPosition = enumOr(prefs.getString(KEY_TOOLBAR_POSITION, null), d.toolbarPosition),
            recentColors = decodeColors(prefs.getString(KEY_RECENT_COLORS, null)),
            lastColor = prefs.getInt(KEY_LAST_COLOR, d.lastColor),
            lastWidth = prefs.getFloat(KEY_LAST_WIDTH, d.lastWidth),
            toolGroupSelections = decodeToolGroupSelections(prefs.getString(KEY_TOOL_GROUPS, null)),
            railOrder = decodeRailIds(prefs.getString(KEY_RAIL_ORDER, null)),
            railHidden = decodeRailIds(prefs.getString(KEY_RAIL_HIDDEN, null)).toSet(),
        )
    }

    fun save(s: AppSettings) {
        val e = prefs.edit()
            .putBoolean(KEY_FINGER_DRAWS, s.fingerDraws)
            .putString(KEY_BARREL, s.barrelAction.name)
            .putBoolean(KEY_HOVER, s.showHover)
            .putString(KEY_SENSITIVITY, s.sensitivity.name)
            .putString(KEY_STROKE_PRECISION, s.strokePrecision.name)
            .putBoolean(KEY_RECOGNIZE_SHAPES, s.recognizeShapes)
            .putBoolean(KEY_SNAP_GRID, s.snapToGrid)
            .putBoolean(KEY_SNAP_ROTATION, s.snapRotation)
        s.penWidths.forEachIndexed { i, w -> e.putFloat(keyPenWidth(i), w) }
        e.putInt(KEY_CUSTOM_COLOR, s.customColor)
        e.putString(KEY_DEFAULT_TOOL, s.defaultTool.name)
        e.putFloat(KEY_MOMENTUM, s.momentum)
        e.putString(KEY_MOMENTUM_CURVE, s.momentumCurve.name)
        e.putFloat(KEY_PAN_SENSITIVITY, s.panSensitivity)
        e.putString(KEY_TOOLBAR_POSITION, s.toolbarPosition.name)
        e.putString(KEY_RECENT_COLORS, s.recentColors.joinToString(",") { it.toString() })
        e.putInt(KEY_LAST_COLOR, s.lastColor)
        e.putFloat(KEY_LAST_WIDTH, s.lastWidth)
        e.putString(KEY_TOOL_GROUPS, encodeToolGroupSelections(s.toolGroupSelections))
        e.putString(KEY_RAIL_ORDER, encodeRailIds(s.railOrder))
        e.putString(KEY_RAIL_HIDDEN, encodeRailIds(s.railHidden))
        e.apply()
    }

    private companion object {
        const val KEY_FINGER_DRAWS = "finger_draws"
        const val KEY_BARREL = "barrel_action"
        const val KEY_HOVER = "show_hover"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_STROKE_PRECISION = "stroke_precision"
        const val KEY_RECOGNIZE_SHAPES = "recognize_shapes"
        const val KEY_SNAP_GRID = "snap_to_grid"
        const val KEY_SNAP_ROTATION = "snap_rotation"
        const val KEY_CUSTOM_COLOR = "custom_color"
        const val KEY_DEFAULT_TOOL = "default_tool"
        // Float-typed since the parameterized control replaced the old discrete enum; a fresh key
        // avoids a ClassCastException on any pref still holding the old enum-name string.
        const val KEY_MOMENTUM = "momentum_factor"
        const val KEY_MOMENTUM_CURVE = "momentum_curve"
        const val KEY_PAN_SENSITIVITY = "pan_sensitivity"
        const val KEY_TOOLBAR_POSITION = "toolbar_position"
        const val KEY_RECENT_COLORS = "recent_colors"
        const val KEY_LAST_COLOR = "last_color"
        const val KEY_LAST_WIDTH = "last_width"
        const val KEY_TOOL_GROUPS = "tool_group_selections"
        const val KEY_RAIL_ORDER = "rail_order"
        const val KEY_RAIL_HIDDEN = "rail_hidden"

        /**
         * Parse the comma-separated ARGB list written by [save], dropping unparsable entries so a
         * corrupt pref degrades to a shorter list rather than a crash.
         */
        fun decodeColors(raw: String?): List<Int> =
            raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.take(AppSettings.MAX_RECENT_COLORS)
                ?: emptyList()

        /** Per-slot SharedPreferences key for the [i]th configurable pen width. */
        fun keyPenWidth(i: Int): String = "pen_width_$i"

        /** Parse an enum by name, falling back to [default] for missing/unknown values. */
        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
    }
}
