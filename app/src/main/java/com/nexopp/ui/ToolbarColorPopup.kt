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
 * The rail's stroke-appearance slot: colour **and** tip size in one drop-down. The face is the size
 * dot from [WidthDot], scaled to the live width and filled with the live colour, so the button shows
 * both settings at a glance. The menu stacks the shared [ColorPaletteRows] over the width slots from
 * [WidthSlotRows] — the two things you change together, in one place.
 */
@Composable
internal fun ColorSizePopupButton(callbacks: ToolbarStyleCallbacks) {
    var editing by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf(-1) }
    val maxPt = (callbacks.widthSlots + callbacks.width).maxOrNull() ?: callbacks.width
    ToolbarPopupButton(
        face = { open ->
            androidx.compose.material3.IconButton(onClick = open) {
                WidthDot(callbacks.width, maxPt, Color(callbacks.color), bordered = true)
            }
        },
    ) { dismiss ->
        MenuHeading("Colour")
        ColorPaletteRows(
            selected = callbacks.color,
            palette = callbacks.palette,
            onPick = { c -> callbacks.onColor(c); dismiss() },
            onEditCustom = { editing = true; dismiss() },
        )
        MenuHeading("Size")
        WidthSlotRows(
            width = callbacks.width,
            widthSlots = callbacks.widthSlots,
            onWidth = { pt -> callbacks.onWidth(pt); dismiss() },
            onEditSlot = { i -> editingSlot = i; dismiss() },
        )
    }
    CustomColorEditor(
        visible = editing,
        palette = callbacks.palette,
        onDismiss = { editing = false },
        onRedefine = callbacks.onRedefineCustom,
    )
    if (editingSlot in callbacks.widthSlots.indices) {
        WidthSlotSliderDialog(
            label = PEN_WIDTH_LABELS[editingSlot],
            initial = callbacks.widthSlots[editingSlot],
            onConfirm = { newPt -> callbacks.onRedefineSlot(editingSlot, newPt); editingSlot = -1 },
            onDismiss = { editingSlot = -1 },
        )
    }
}
