package com.xopp.android.ui

import android.content.Context
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.MomentumStrength
import com.xopp.android.render.PressureSensitivity

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
    /** The three user-configurable pen-tip widths (pt) behind the S/M/L size slots, in slot order. */
    val penWidths: List<Float> = DEFAULT_PEN_WIDTHS,
    /** The user-defined colour (opaque ARGB) behind the palette's editable custom slot. */
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    /** Which tool is active when a document first opens. */
    val defaultTool: EditorTool = EditorTool.PEN,
    /** How far a released pan keeps gliding (momentum scrolling). */
    val momentum: MomentumStrength = MomentumStrength.NORMAL,
) {
    companion object {
        /** Factory defaults for the three pen-width slots — the old fixed S/M/L values. */
        val DEFAULT_PEN_WIDTHS: List<Float> = listOf(0.85f, 1.5f, 2.6f)

        /** Factory default for the custom colour slot — a violet not already in the fixed palette. */
        val DEFAULT_CUSTOM_COLOR: Int = 0xFF9C27B0.toInt()
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
            penWidths = d.penWidths.mapIndexed { i, w -> prefs.getFloat(keyPenWidth(i), w) },
            customColor = prefs.getInt(KEY_CUSTOM_COLOR, d.customColor),
            defaultTool = enumOr(prefs.getString(KEY_DEFAULT_TOOL, null), d.defaultTool),
            momentum = enumOr(prefs.getString(KEY_MOMENTUM, null), d.momentum),
        )
    }

    fun save(s: AppSettings) {
        val e = prefs.edit()
            .putBoolean(KEY_FINGER_DRAWS, s.fingerDraws)
            .putString(KEY_BARREL, s.barrelAction.name)
            .putBoolean(KEY_HOVER, s.showHover)
            .putString(KEY_SENSITIVITY, s.sensitivity.name)
        s.penWidths.forEachIndexed { i, w -> e.putFloat(keyPenWidth(i), w) }
        e.putInt(KEY_CUSTOM_COLOR, s.customColor)
        e.putString(KEY_DEFAULT_TOOL, s.defaultTool.name)
        e.putString(KEY_MOMENTUM, s.momentum.name)
        e.apply()
    }

    private companion object {
        const val KEY_FINGER_DRAWS = "finger_draws"
        const val KEY_BARREL = "barrel_action"
        const val KEY_HOVER = "show_hover"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_CUSTOM_COLOR = "custom_color"
        const val KEY_DEFAULT_TOOL = "default_tool"
        const val KEY_MOMENTUM = "momentum"

        /** Per-slot SharedPreferences key for the [i]th configurable pen width. */
        fun keyPenWidth(i: Int): String = "pen_width_$i"

        /** Parse an enum by name, falling back to [default] for missing/unknown values. */
        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
    }
}
