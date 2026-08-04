package com.xopp.android.ui

import com.xopp.android.format.model.LineStyle
import com.xopp.android.render.DrawingSurfaceView

/**
 * A named snapshot of the whole tool configuration — the tool itself plus every knob the rail can
 * turn on it (colour, width, line style, fill).
 *
 * A preset is *only* a value: capturing one reads the live editor state, applying one writes the
 * same fields back through the same paths [applyPaletteAction] uses. Nothing here talks to storage
 * (that's [AppSettings]) or to the UI, which is what keeps it unit-testable off-device.
 */
data class ToolPreset(
    val id: String,
    val name: String,
    val tool: EditorTool,
    val colorArgb: Int,
    val widthPt: Float,
    val lineStyle: LineStyle = LineStyle.PLAIN,
    val fillEnabled: Boolean = false,
    val fillAlpha: Int = DEFAULT_FILL_ALPHA,
) {
    /** The fill to hand the canvas: `null` when fill is off, mirroring [AppSettings.currentFill]. */
    val currentFill: Int? get() = if (fillEnabled) fillAlpha else null

    companion object {
        /**
         * Snapshot the live pen. The tool/colour/width/style come from [ui]; fill lives in
         * [settings] because it is persisted rather than held per-session.
         */
        fun capture(
            ui: EditorUiState,
            settings: AppSettings,
            name: String,
            id: String = slugId(name),
        ): ToolPreset = ToolPreset(
            id = id,
            name = name,
            tool = ui.tool,
            colorArgb = ui.color,
            widthPt = ui.width,
            lineStyle = ui.lineStyle,
            fillEnabled = settings.fillEnabled,
            fillAlpha = settings.fillAlpha,
        )

        /** A stable kebab-case handle for a preset named [name], falling back to `preset`. */
        fun slugId(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("").trim('-').replace(Regex("-+"), "-")
                .ifEmpty { "preset" }
    }
}

/** Restore the session-only half of the preset onto [ui]. */
fun ToolPreset.applyToState(ui: EditorUiState) {
    ui.tool = tool
    ui.color = colorArgb
    ui.width = widthPt
    ui.lineStyle = lineStyle
}

/** The persisted half: the settings that [settings] becomes once this preset is active. */
fun ToolPreset.applyToSettings(settings: AppSettings): AppSettings =
    settings.withColorUsed(colorArgb)
        .copy(lastWidth = widthPt, fillEnabled = fillEnabled, fillAlpha = fillAlpha)

/**
 * Activate [preset] — the one entry point the rail and a radial-palette slot both call.
 *
 * Every write below is the same one the toolbar makes for that knob (see `EditorRegions`), so a
 * preset can't mean anything the user couldn't have set by hand.
 */
fun applyToolPreset(
    preset: ToolPreset,
    ui: EditorUiState,
    surface: DrawingSurfaceView?,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    preset.applyToState(ui)
    surface?.let {
        it.applyTool(preset.tool)
        it.colorArgb = preset.colorArgb
        it.baseWidthPt = preset.widthPt
        it.currentLineStyle = preset.lineStyle
        it.currentFill = preset.currentFill
    }
    onSettingsChange(preset.applyToSettings(settings))
}
