package com.xopp.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The palette manager: the row of saved palettes above the ring editor, plus the buttons that add,
 * rename, reorder and delete them — the same shape as the presets rail, one chip per palette.
 *
 * Two different "current" palettes live here and are deliberately separate: the **edited** one (the
 * chip that is selected, whose rings the diagram below shows) and the **active** one (the palette
 * the pen actually opens, marked in its chip's label). Every edit goes through the pure helpers in
 * `PaletteList.kt` and back out via [onSet], which is what persists it.
 */
@Composable
fun PaletteManagerRow(
    set: PaletteSet,
    editing: Int,
    onEdit: (Int) -> Unit,
    onSet: (PaletteSet) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        set.palettes.forEachIndexed { i, palette ->
            FilterChip(
                selected = i == editing,
                onClick = { onEdit(i) },
                label = { Text(paletteChipLabel(palette, i == set.activeIndex)) },
            )
        }
    }
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSet(addPalette(set)); onEdit(set.palettes.size) }) {
            Icon(Icons.Filled.Add, contentDescription = "Add palette")
        }
        IconButton(onClick = { renaming = true }) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename palette")
        }
        IconButton(onClick = { onSet(movePalette(set, editing, -1)); onEdit(editing - 1) }, enabled = editing > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move palette earlier")
        }
        IconButton(
            onClick = { onSet(movePalette(set, editing, 1)); onEdit(editing + 1) },
            enabled = editing < set.palettes.lastIndex,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move palette later")
        }
        IconButton(onClick = { deleting = true }, enabled = set.palettes.size > 1) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete palette")
        }
    }
    OutlinedButton(
        onClick = { onSet(activatePalette(set, editing)) },
        enabled = editing != set.activeIndex,
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("Use this palette on the pen") }
    if (renaming) {
        PaletteRenameDialog(
            current = set.palettes.getOrNull(editing)?.name.orEmpty(),
            onConfirm = { onSet(renamePalette(set, editing, it)); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        val name = set.palettes.getOrNull(editing)?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("Its slot assignments are lost. Your other palettes are untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    onSet(removePalette(set, editing))
                    onEdit((editing - 1).coerceAtLeast(0))
                    deleting = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** A chip's label: the palette's name, with a dot marking the one the pen opens. */
internal fun paletteChipLabel(palette: RadialPalette, isActive: Boolean): String =
    if (isActive) "● ${palette.name}" else palette.name

/** The rename step: a single-line name field over the palette being edited. */
@Composable
private fun PaletteRenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename palette") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
