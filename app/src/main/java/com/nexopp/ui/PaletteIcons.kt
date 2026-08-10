package com.nexopp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The picture on a palette slot, drawn from the **same Material icon set as the toolbar rail** so a
 * tool wears one face everywhere: whatever `EditorTool.icon` puts on the rail button is what the
 * ring shows. Actions the rail has no button for (undo, page ops) pick the icon the toolbar popup
 * uses, or the closest Material equivalent.
 *
 * `null` means the slot has no icon and falls back to [PaletteFace.glyph] — the colour swatches
 * (which paint the whole mark) and the width slots (which read as a number).
 */
fun PaletteAction.icon(): ImageVector? = when (this) {
    is PaletteAction.SelectTool -> tool.icon
    is PaletteAction.ToggleTool -> tool.icon
    is PaletteAction.SetColor -> null
    is PaletteAction.SetWidth -> null
    PaletteAction.Undo -> Icons.Filled.Undo
    PaletteAction.Redo -> Icons.Filled.Redo
    PaletteAction.ToggleFullPage -> Icons.Filled.Fullscreen
    is PaletteAction.Page -> op.icon()
    is PaletteAction.ApplyPreset -> Icons.Filled.Bookmark
    // No icon: a slot action's number *is* its identity, so it falls back to the "★1" glyph.
    is PaletteAction.ApplyPresetSlot -> null
    is PaletteAction.SwitchPalette -> Icons.Filled.Adjust
}

/**
 * The colour to fill this slot's icon with, or `null` for the default icon paint.
 *
 * Only preset slots tint: a preset wears the colour it will apply ([presetColors] maps a preset id
 * to its `colorArgb`), so a ring full of bookmarks is still readable at a glance. A preset the map
 * doesn't know — one deleted since the slot was bound — falls back to the plain icon.
 *
 * The colour is passed through [legibleOnSlot] first, because the slot disc is near-black and a
 * black pen preset would otherwise draw an invisible icon.
 */
fun PaletteAction.iconTintArgb(presetColors: Map<String, Int>): Int? = when (this) {
    is PaletteAction.ApplyPreset -> presetColors[presetId]?.let { legibleOnSlot(it) }
    else -> null
}

/**
 * [argb] lightened just enough to read against the dark slot disc: colours below [MIN_LUMINANCE]
 * are mixed toward white until they clear it, and the result is always fully opaque. Colours that
 * are already light come back untouched, so a preset's hue survives.
 */
fun legibleOnSlot(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    if (luminance >= MIN_LUMINANCE) return 0xFF000000.toInt() or (argb and 0x00FFFFFF)
    // Mix t of the way to white; solving the luminance blend for t hits the floor exactly.
    val t = ((MIN_LUMINANCE - luminance) / (1.0 - luminance)).coerceIn(0.0, 1.0)
    fun mix(c: Int) = (c + (255 - c) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
}

/** How light a preset's icon must be to stand off the slot disc (relative luminance, 0…1). */
private const val MIN_LUMINANCE = 0.45

private fun PalettePageOp.icon(): ImageVector = when (this) {
    PalettePageOp.NEW_AFTER -> Icons.Filled.PostAdd
    PalettePageOp.NEW_BEFORE -> Icons.Filled.NoteAdd
    PalettePageOp.DUPLICATE -> Icons.Filled.ContentCopy
    PalettePageOp.DELETE -> Icons.Filled.DeleteForever
    PalettePageOp.NEXT -> Icons.Filled.KeyboardArrowDown
    PalettePageOp.PREVIOUS -> Icons.Filled.KeyboardArrowUp
}
