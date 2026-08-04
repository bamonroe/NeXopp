package com.xopp.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The sheet that fills one radial-palette slot. Opened by tapping a slot in [PaletteSection], it
 * lists every assignable action — the tools, the edit and page operations, the shared colour
 * swatches and a width slider — plus a **Clear** row that empties the slot.
 *
 * It reports the choice through [onPick] (`null` meaning "clear") and leaves the commit to the
 * host, which folds it into `settings.radialPalette` so persistence and the editor push already
 * work with no extra plumbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteActionPickerSheet(
    slot: RadialSlot,
    current: PaletteAction?,
    palette: ColorPaletteState,
    presets: List<ToolPreset> = emptyList(),
    palettes: List<RadialPalette> = emptyList(),
    onPick: (PaletteAction?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
            item {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(slot.title(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        current?.let { "Currently: ${it.describeAction(presets)}" } ?: "Currently empty",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { ChoiceRow("Clear slot", onClick = { onPick(null) }) }
            item { PickerHeader("Colour") }
            item {
                ColorPaletteRows(
                    selected = (current as? PaletteAction.SetColor)?.argb,
                    palette = palette,
                    onPick = { onPick(PaletteAction.SetColor(it)) },
                    onEditCustom = {},
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            item { PickerHeader("Width") }
            item {
                WidthChoice(
                    initial = (current as? PaletteAction.SetWidth)?.widthPt,
                    onPick = { onPick(PaletteAction.SetWidth(it)) },
                )
            }
            for (group in paletteActionGroups(presets, palettes)) {
                item(key = group.title) { PickerHeader(group.title) }
                items(group.choices, key = { group.title + it.label }) { choice ->
                    ChoiceRow(
                        label = choice.label,
                        selected = choice.action == current,
                        onClick = { onPick(choice.action) },
                    )
                }
            }
            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }
}

/** The sheet's title for a slot: which ring and which position, counted from straight up. */
private fun RadialSlot.title(): String {
    val ringName = if (ring == RadialRing.INNER) "Inner" else "Outer"
    return "$ringName ring · slot ${index + 1} of ${ring.slotCount}"
}

@Composable
private fun PickerHeader(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** One tappable action row; the live assignment is marked so the sheet shows what the slot holds. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        if (selected) "✓ $label" else label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** The width action's value: a slider over the toolbar's own bounds, committed by the Set button. */
@Composable
private fun WidthChoice(initial: Float?, onPick: (Float) -> Unit) {
    var value by remember { mutableStateOf(initial ?: DEFAULT_PALETTE_WIDTH) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = value,
            onValueChange = { value = it.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX) },
            valueRange = PEN_WIDTH_MIN..PEN_WIDTH_MAX,
        )
        TextButton(onClick = { onPick(value) }) { Text("Set width ${ptLabel(value)} pt") }
    }
}

/** Where the width slider starts when the slot doesn't already hold a width. */
private const val DEFAULT_PALETTE_WIDTH: Float = 1.5f
