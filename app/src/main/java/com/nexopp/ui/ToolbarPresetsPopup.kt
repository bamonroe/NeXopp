package com.nexopp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The presets rail slot: the user's saved [ToolPreset]s, each one tap away.
 *
 * The menu lists every saved preset numbered by its slot (what a position-based palette action
 * fires), with a swatch in its colour and a dot scaled to its width (the
 * same [TipDot] language the size popup uses), then a "save current tool" row with an inline name
 * field. Reorder arrows and a delete button sit on each row; every edit goes through the pure
 * helpers in `ToolPresetList.kt` and back out via [onPresets], which is what persists it.
 */
@Composable
internal fun PresetsPopupButton(
    presets: List<ToolPreset>,
    onPresets: (List<ToolPreset>) -> Unit,
    onActivate: (ToolPreset) -> Unit,
    onCapture: (String) -> ToolPreset,
) {
    var newName by remember { mutableStateOf("") }
    ToolbarPopupButton(
        icon = Icons.Filled.Bookmark,
        contentDescription = "Presets",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { dismiss ->
        MenuHeading(if (presets.isEmpty()) "No presets saved yet" else "Presets")
        presets.forEachIndexed { i, preset ->
            PresetRow(
                preset = preset,
                slot = i + 1,
                canMoveUp = i > 0,
                canMoveDown = i < presets.lastIndex,
                onActivate = { onActivate(preset); dismiss() },
                onOverwrite = {
                    onPresets(overwriteToolPreset(presets, preset.id, onCapture(preset.name)))
                },
                onMove = { delta -> onPresets(moveToolPreset(presets, i, delta)) },
                onDelete = { onPresets(removeToolPreset(presets, preset.id)) },
            )
        }
        SavePresetRow(
            name = newName,
            onName = { newName = it },
            onSave = {
                val name = newName.trim().ifEmpty { "Preset ${presets.size + 1}" }
                onPresets(addToolPreset(presets, onCapture(name)))
                newName = ""
            },
        )
    }
}

/**
 * One saved preset: swatch, name, reorder arrows, delete. Tapping the row activates the preset;
 * long-pressing it overwrites the slot with the live tool, so presets can be tuned in place instead
 * of only being appended under a new name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(
    preset: ToolPreset,
    slot: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActivate: () -> Unit,
    onOverwrite: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onActivate, onLongClick = onOverwrite)
                .padding(vertical = 8.dp)
                .width(180.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetSwatch(preset)
            Spacer(Modifier.width(12.dp))
            // The slot number is what a position-based palette action fires, so it leads the row.
            Text(
                "$slot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(preset.name, style = MaterialTheme.typography.bodyMedium)
        }
        ReorderControls(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            itemName = preset.name,
            onMove = onMove,
            onDelete = onDelete,
        )
    }
}

/**
 * A preset's face: a dot in the preset's colour, sized against the width slider's own maximum so a
 * fine pen and a fat marker read differently at a glance. Outlined so a white preset is still visible.
 */
@Composable
private fun PresetSwatch(preset: ToolPreset) {
    WidthDot(preset.widthPt, PEN_WIDTH_MAX, Color(preset.colorArgb), bordered = true)
}

/** The "save the live tool" row: a name field plus the button that captures it. */
@Composable
private fun SavePresetRow(name: String, onName: (String) -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            label = { Text("Save current tool as…") },
            modifier = Modifier.width(200.dp),
        )
        IconButton(onClick = onSave) {
            Icon(Icons.Filled.Add, contentDescription = "Save preset")
        }
    }
}

