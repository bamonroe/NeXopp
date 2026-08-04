package com.xopp.android.ui

/**
 * The catalogue of assignable [PaletteAction]s — what the slot picker offers, and how each action
 * reads in prose. Kept free of Compose so the list of choices (and every label in it) is
 * unit-testable on the JVM; the sheet in `PaletteActionPicker.kt` only renders what it finds here.
 *
 * The colour and width actions are *not* listed: they carry a value the user has to dial in, so the
 * sheet hands those to the shared colour palette and the width slider instead of a fixed row.
 */
data class PaletteActionChoice(val label: String, val action: PaletteAction)

/** One titled block of the picker — the sheet renders the groups in order, with headers. */
data class PaletteActionGroup(val title: String, val choices: List<PaletteActionChoice>)

/** Every fixed (value-free) action a slot can hold, grouped the way the picker shows them. */
fun paletteActionGroups(presets: List<ToolPreset> = emptyList()): List<PaletteActionGroup> = listOfNotNull(
    presets.takeIf { it.isNotEmpty() }?.let { saved ->
        PaletteActionGroup(
            "Preset",
            saved.map { PaletteActionChoice(it.name, PaletteAction.ApplyPreset(it.id)) },
        )
    },
    PaletteActionGroup(
        "Select tool",
        EditorTool.entries.map { PaletteActionChoice(it.label, PaletteAction.SelectTool(it)) },
    ),
    PaletteActionGroup(
        "Toggle tool",
        EditorTool.entries.map { PaletteActionChoice(it.label, PaletteAction.ToggleTool(it)) },
    ),
    PaletteActionGroup(
        "Edit",
        listOf(
            PaletteActionChoice("Undo", PaletteAction.Undo),
            PaletteActionChoice("Redo", PaletteAction.Redo),
            PaletteActionChoice("Toggle full page", PaletteAction.ToggleFullPage),
        ),
    ),
    PaletteActionGroup(
        "Page",
        PalettePageOp.entries.map { PaletteActionChoice(it.label, PaletteAction.Page(it)) },
    ),
)

/** Human-readable label for a page operation, matching the toolbar's pages popup wording. */
val PalettePageOp.label: String
    get() = when (this) {
        PalettePageOp.NEW_AFTER -> "New page after"
        PalettePageOp.NEW_BEFORE -> "New page before"
        PalettePageOp.DUPLICATE -> "Duplicate page"
        PalettePageOp.DELETE -> "Delete page"
        PalettePageOp.NEXT -> "Next page"
        PalettePageOp.PREVIOUS -> "Previous page"
    }

/** How an assigned action reads in the "Selected slot" line and in the picker's current-value row. */
fun PaletteAction.describeAction(presets: List<ToolPreset> = emptyList()): String = when (this) {
    is PaletteAction.SelectTool -> "Select ${tool.label.lowercase()}"
    is PaletteAction.ToggleTool -> "Toggle ${tool.label.lowercase()}"
    is PaletteAction.SetColor -> "Colour #%06X".format(argb and 0xFFFFFF)
    is PaletteAction.SetWidth -> "Width ${ptLabel(widthPt)} pt"
    PaletteAction.Undo -> "Undo"
    PaletteAction.Redo -> "Redo"
    PaletteAction.ToggleFullPage -> "Toggle full page"
    is PaletteAction.Page -> op.label
    // Named where the preset still exists; a slot pointing at a deleted preset says so plainly.
    is PaletteAction.ApplyPreset ->
        presets.firstOrNull { it.id == presetId }?.let { "Preset ${it.name}" } ?: "Preset (deleted)"
}
