package com.xopp.android.ui

import android.content.Context
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.BarrelDoubleAction
import com.xopp.android.render.GuideKind
import com.xopp.android.render.Momentum
import com.xopp.android.render.MomentumCurve
import com.xopp.android.render.PaletteInvocation
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

/** Which Material 3 colour scheme the app's chrome uses. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
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
    /** What a rapid double-click of that button does (recognised only with the tip off the glass). */
    val barrelDoubleAction: BarrelDoubleAction = BarrelDoubleAction.UNDO,
    /** Which gesture opens the radial palette — for styluses with no barrel button to double-click. */
    val paletteInvocation: PaletteInvocation = PaletteInvocation.BARREL_DOUBLE_CLICK,
    /** Show a preview ring where a hovering stylus will land. */
    val showHover: Boolean = true,
    /** Buzz as a radial-palette flick crosses into a new slot, and again when it commits. */
    val paletteHaptics: Boolean = true,
    /** How pen pressure maps to stroke width. */
    val sensitivity: PressureSensitivity = PressureSensitivity.LINEAR,
    /** How much digitiser detail a freehand stroke keeps (fidelity vs. file size). */
    val strokePrecision: StrokePrecision = StrokePrecision.DEFAULT,
    /** Snap a finished freehand stroke to the primitive it resembles (line, circle, rectangle…). */
    val recognizeShapes: Boolean = false,
    /** Pages shown side by side in the page overview: 1 is the plain single-page stack. */
    val pageColumns: Int = 1,
    /** Pull shape-tool endpoints onto the page background's ruling (grid/lined sheets only). */
    val snapToGrid: Boolean = false,
    /** Pull a selection's rotate handle onto 15-degree increments. */
    val snapRotation: Boolean = false,
    /** Which on-canvas drawing guide (setsquare/compass) is laid on the page, restored on launch. */
    val guideKind: GuideKind = GuideKind.NONE,
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
    /** Whether new strokes/shapes are flooded with fill, restored on the next launch. */
    val fillEnabled: Boolean = false,
    /** The fill alpha (1..255) last chosen, kept while [fillEnabled] is off. */
    val fillAlpha: Int = DEFAULT_FILL_ALPHA,
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
    /**
     * The persisted `OpenDocumentTree` URI of the folder audio sidecars are kept in (empty = none
     * nominated). A `.xopp` references its recordings by bare file name, so they have to live in a
     * folder we can both read and write — see [com.xopp.android.audio.AudioStore].
     */
    val audioFolderUri: String = "",
    /** Light, dark, or follow the system — applied to the whole app's Material 3 scheme. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * The user's radial palettes in display order, never empty (see [RadialPalette]); which one the
     * pen opens is [activePaletteIndex].
     */
    val palettes: List<RadialPalette> = listOf(RadialPalette.default()),
    /** Which entry of [palettes] the barrel double-click opens at the pen tip. */
    val activePaletteIndex: Int = 0,
    /** The user's saved tool snapshots, in display order (see [ToolPreset]). */
    val presets: List<ToolPreset> = emptyList(),
) {
    /** The fill alpha to draw with, or null when fill is off — the two fill fields as one value. */
    val currentFill: Int? get() = if (fillEnabled) fillAlpha else null

    /** The palette list and its active index as one value — what the pure edits in `PaletteList.kt` take. */
    val paletteSet: PaletteSet get() = PaletteSet(palettes, activePaletteIndex).normalized()

    /** The palette the pen opens: the active entry, tolerating a stale index or an empty list. */
    val radialPalette: RadialPalette get() = paletteSet.active

    /** This settings object with [set] adopted, normalised so the list stays non-empty and in range. */
    fun withPalettes(set: PaletteSet): AppSettings = set.normalized().let {
        copy(palettes = it.palettes, activePaletteIndex = it.activeIndex)
    }

    /**
     * This settings object with [color] pushed to the front of [recentColors] — de-duplicated and
     * truncated to [MAX_RECENT_COLORS]. Every picker records its choice here, so recents are shared;
     * only the pen's own picks pass [asPen], which also makes it the [lastColor] restored on launch.
     */
    fun withColorUsed(color: Int, asPen: Boolean = true): AppSettings = copy(
        recentColors = (listOf(color) + recentColors.filter { it != color }).take(MAX_RECENT_COLORS),
        lastColor = if (asPen) color else lastColor,
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
            barrelDoubleAction = enumOr(prefs.getString(KEY_BARREL_DOUBLE, null), d.barrelDoubleAction),
            paletteInvocation = enumOr(prefs.getString(KEY_PALETTE_INVOCATION, null), d.paletteInvocation),
            showHover = prefs.getBoolean(KEY_HOVER, d.showHover),
            paletteHaptics = prefs.getBoolean(KEY_PALETTE_HAPTICS, d.paletteHaptics),
            sensitivity = enumOr(prefs.getString(KEY_SENSITIVITY, null), d.sensitivity),
            strokePrecision = enumOr(prefs.getString(KEY_STROKE_PRECISION, null), d.strokePrecision),
            recognizeShapes = prefs.getBoolean(KEY_RECOGNIZE_SHAPES, d.recognizeShapes),
            pageColumns = prefs.getInt(KEY_PAGE_COLUMNS, d.pageColumns),
            snapToGrid = prefs.getBoolean(KEY_SNAP_GRID, d.snapToGrid),
            snapRotation = prefs.getBoolean(KEY_SNAP_ROTATION, d.snapRotation),
            guideKind = enumOr(prefs.getString(KEY_GUIDE_KIND, null), d.guideKind),
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
            fillEnabled = prefs.getBoolean(KEY_FILL_ENABLED, d.fillEnabled),
            fillAlpha = prefs.getInt(KEY_FILL_ALPHA, d.fillAlpha).coerceIn(1, 255),
            toolGroupSelections = decodeToolGroupSelections(prefs.getString(KEY_TOOL_GROUPS, null)),
            railOrder = decodeRailIds(prefs.getString(KEY_RAIL_ORDER, null)),
            railHidden = decodeRailIds(prefs.getString(KEY_RAIL_HIDDEN, null)).toSet(),
            audioFolderUri = prefs.getString(KEY_AUDIO_FOLDER, d.audioFolderUri) ?: d.audioFolderUri,
            themeMode = enumOr(prefs.getString(KEY_THEME_MODE, null), d.themeMode),
            presets = decodeToolPresets(prefs.getString(KEY_PRESETS, null)),
        ).withPalettes(loadPaletteSet())
    }

    /**
     * The saved palette list, or — for a pref file written before palettes became a list — the single
     * palette that build stored, migrated into a one-entry list so nobody loses their setup.
     */
    private fun loadPaletteSet(): PaletteSet = migratedPaletteSet(
        listRaw = prefs.getString(KEY_PALETTES, null),
        legacyRaw = prefs.getString(KEY_RADIAL_PALETTE, null),
        activeIndex = prefs.getInt(KEY_ACTIVE_PALETTE, 0),
    )

    fun save(s: AppSettings) {
        val e = prefs.edit()
            .putBoolean(KEY_FINGER_DRAWS, s.fingerDraws)
            .putString(KEY_BARREL, s.barrelAction.name)
            .putString(KEY_BARREL_DOUBLE, s.barrelDoubleAction.name)
            .putString(KEY_PALETTE_INVOCATION, s.paletteInvocation.name)
            .putBoolean(KEY_HOVER, s.showHover)
            .putBoolean(KEY_PALETTE_HAPTICS, s.paletteHaptics)
            .putString(KEY_SENSITIVITY, s.sensitivity.name)
            .putString(KEY_STROKE_PRECISION, s.strokePrecision.name)
            .putBoolean(KEY_RECOGNIZE_SHAPES, s.recognizeShapes)
            .putInt(KEY_PAGE_COLUMNS, s.pageColumns)
            .putBoolean(KEY_SNAP_GRID, s.snapToGrid)
            .putBoolean(KEY_SNAP_ROTATION, s.snapRotation)
            .putString(KEY_GUIDE_KIND, s.guideKind.name)
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
        e.putBoolean(KEY_FILL_ENABLED, s.fillEnabled)
        e.putInt(KEY_FILL_ALPHA, s.fillAlpha)
        e.putString(KEY_TOOL_GROUPS, encodeToolGroupSelections(s.toolGroupSelections))
        e.putString(KEY_RAIL_ORDER, encodeRailIds(s.railOrder))
        e.putString(KEY_RAIL_HIDDEN, encodeRailIds(s.railHidden))
        e.putString(KEY_AUDIO_FOLDER, s.audioFolderUri)
        e.putString(KEY_THEME_MODE, s.themeMode.name)
        e.putString(KEY_PALETTES, encodeRadialPalettes(s.palettes))
        e.putInt(KEY_ACTIVE_PALETTE, s.activePaletteIndex)
        // The pre-list key is dropped once the list exists, so the migration above runs exactly once.
        e.remove(KEY_RADIAL_PALETTE)
        e.putString(KEY_PRESETS, encodeToolPresets(s.presets))
        e.apply()
    }

    private companion object {
        const val KEY_FINGER_DRAWS = "finger_draws"
        const val KEY_BARREL = "barrel_action"
        const val KEY_BARREL_DOUBLE = "barrel_double_action"
        const val KEY_PALETTE_INVOCATION = "palette_invocation"
        const val KEY_HOVER = "show_hover"
        const val KEY_PALETTE_HAPTICS = "palette_haptics"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_STROKE_PRECISION = "stroke_precision"
        const val KEY_RECOGNIZE_SHAPES = "recognize_shapes"
        const val KEY_PAGE_COLUMNS = "page_columns"
        const val KEY_SNAP_GRID = "snap_to_grid"
        const val KEY_SNAP_ROTATION = "snap_rotation"
        const val KEY_GUIDE_KIND = "guide_kind"
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
        const val KEY_FILL_ENABLED = "fill_enabled"
        const val KEY_FILL_ALPHA = "fill_alpha"
        const val KEY_TOOL_GROUPS = "tool_group_selections"
        const val KEY_RAIL_ORDER = "rail_order"
        const val KEY_RAIL_HIDDEN = "rail_hidden"
        const val KEY_AUDIO_FOLDER = "audio_folder_uri"
        const val KEY_THEME_MODE = "theme_mode"
        /** Pre-list key: one palette. Still read (and then cleared) so old installs migrate. */
        const val KEY_RADIAL_PALETTE = "radial_palette"
        const val KEY_PALETTES = "radial_palettes"
        const val KEY_ACTIVE_PALETTE = "radial_palette_active"
        const val KEY_PRESETS = "tool_presets"

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
