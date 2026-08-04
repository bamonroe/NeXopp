package com.xopp.android.ui

import com.xopp.android.render.DrawingSurfaceView

/**
 * Runs the [PaletteAction] a radial-palette flick landed on.
 *
 * The palette introduces no behaviour of its own — every case here is the same edit the toolbar
 * already makes (see the rail wiring in `EditorRegions`), just reached by a flick instead of a tap.
 * Keeping the mapping in one function is what makes that true: the toolbar and the palette can't
 * drift apart into two different meanings of "set the colour".
 */
fun applyPaletteAction(
    action: PaletteAction,
    ui: EditorUiState,
    surface: DrawingSurfaceView,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    when (action) {
        is PaletteAction.SelectTool -> { ui.tool = action.tool; surface.applyTool(ui.tool) }
        is PaletteAction.ToggleTool -> { ui.toggleTool(action.tool); surface.applyTool(ui.tool) }
        is PaletteAction.SetColor -> {
            ui.color = action.argb
            surface.colorArgb = action.argb
            onSettingsChange(settings.withColorUsed(action.argb))
        }
        is PaletteAction.SetWidth -> {
            ui.width = action.widthPt
            surface.baseWidthPt = action.widthPt
            onSettingsChange(settings.copy(lastWidth = action.widthPt))
        }
        PaletteAction.Undo -> surface.undo()
        PaletteAction.Redo -> surface.redo()
        PaletteAction.ToggleFullPage -> ui.fullPage = !ui.fullPage
        is PaletteAction.Page -> applyPageOp(action.op, surface)
        // A slot naming a preset that has since been deleted is a no-op rather than an error.
        is PaletteAction.ApplyPreset -> settings.presets.firstOrNull { it.id == action.presetId }
            ?.let { applyToolPreset(it, ui, surface, settings, onSettingsChange) }
        is PaletteAction.SwitchPalette -> switchPalette(action.paletteName, surface, settings, onSettingsChange)
    }
}

/**
 * Make the palette named [name] active and pop it straight back up where the last one stood, so the
 * switch costs one flick rather than a flick and a fresh summoning gesture. A name no palette
 * carries any more (deleted or renamed) is a no-op, matching the deleted-preset case above.
 */
private fun switchPalette(
    name: String,
    surface: DrawingSurfaceView,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val index = settings.palettes.indexOfFirst { it.name == name }
    if (index < 0) return
    val updated = settings.withPalettes(activatePalette(settings.paletteSet, index))
    onSettingsChange(updated)
    // Pushed in directly rather than waiting for the settings round trip to reach `applySettings`:
    // the reopen happens now, and recomposition is a frame or more away.
    surface.palette = updated.radialPalette
    surface.reopenPalette(updated.radialPalette)
}

/** The page half of [applyPaletteAction] — split out to keep each function one screen long. */
private fun applyPageOp(op: PalettePageOp, surface: DrawingSurfaceView) {
    when (op) {
        PalettePageOp.NEW_AFTER -> surface.addPage()
        PalettePageOp.NEW_BEFORE -> surface.addPageBefore()
        PalettePageOp.DUPLICATE -> surface.duplicatePage()
        PalettePageOp.DELETE -> surface.removePage()
        PalettePageOp.NEXT -> surface.goToNextPage()
        PalettePageOp.PREVIOUS -> surface.goToPreviousPage()
    }
}
