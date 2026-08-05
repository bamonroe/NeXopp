package com.xopp.android.ui

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
    is PaletteAction.SwitchPalette -> Icons.Filled.Adjust
}

private fun PalettePageOp.icon(): ImageVector = when (this) {
    PalettePageOp.NEW_AFTER -> Icons.Filled.PostAdd
    PalettePageOp.NEW_BEFORE -> Icons.Filled.NoteAdd
    PalettePageOp.DUPLICATE -> Icons.Filled.ContentCopy
    PalettePageOp.DELETE -> Icons.Filled.DeleteForever
    PalettePageOp.NEXT -> Icons.Filled.KeyboardArrowDown
    PalettePageOp.PREVIOUS -> Icons.Filled.KeyboardArrowUp
}
