package com.nexopp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The pen palette offered in the chrome. Colours are opaque ARGB; highlighter renders them translucent. */
val PEN_COLORS: List<Int> = listOf(
    0xFF000000.toInt(), // black
    0xFFE00000.toInt(), // red
    0xFF2060E0.toInt(), // blue
    0xFF1E9E1E.toInt(), // green
    0xFFF08000.toInt(), // orange
    0xFFF0D000.toInt(), // yellow
)

/**
 * The pen's colour button: the shared [ColorPaletteRows] in a drop-down. The palette itself (fixed
 * swatches, the editable custom slot, recents) lives in `ColorPalette.kt` and is the same component
 * the text-box dialog and the selection recolour menu use; this only adds the button and the menu
 * around it, and the extra step of pushing the pick onto the canvas via [onColor].
 */
@Composable
internal fun ColorPopupButton(
    color: Int,
    onColor: (Int) -> Unit,
    palette: ColorPaletteState,
    onRedefineCustom: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    ToolbarPopupButton(
        icon = Icons.Filled.Circle,
        contentDescription = "Colour",
        tint = Color(color),
    ) { dismiss ->
        ColorPaletteRows(
            selected = color,
            palette = palette,
            onPick = { c -> onColor(c); dismiss() },
            onEditCustom = { editing = true; dismiss() },
        )
    }
    CustomColorEditor(
        visible = editing,
        palette = palette,
        onDismiss = { editing = false },
        onRedefine = onRedefineCustom,
    )
}
