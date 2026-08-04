package com.xopp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.xopp.android.render.LayerInfo

/**
 * The layer manager: a top-down list of the visible page's layers, each row toggling visibility,
 * selecting the active layer (where new ink lands), reordering (up/down z-order), renaming, and
 * deleting; plus "Add layer" and — when something is selected — "Move selection here".
 */
@Composable
internal fun LayersPopupButton(
    layers: List<LayerInfo>,
    hasSelection: Boolean,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Int) -> Unit,
    onRenameLayer: (Int, String) -> Unit,
    onMoveLayer: (Int, Int) -> Unit,
    onActivateLayer: (Int) -> Unit,
    onToggleLayerHidden: (Int, Boolean) -> Unit,
    onMoveSelectionToLayer: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(-1) }
    var renameLabel by remember { mutableStateOf("") }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Layers, contentDescription = "Layers")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Layers (top first)")
            // Show top layer first (model is bottom-up), so the list matches z-order on screen.
            for (info in layers.asReversed()) {
                LayerRow(
                    info = info,
                    canMoveUp = info.index < layers.lastIndex,
                    canMoveDown = info.index > 0,
                    canDelete = layers.size > 1,
                    hasSelection = hasSelection,
                    onActivate = { onActivateLayer(info.index) },
                    onToggleHidden = { onToggleLayerHidden(info.index, info.visible) },
                    onMoveUp = { onMoveLayer(info.index, info.index + 1) },
                    onMoveDown = { onMoveLayer(info.index, info.index - 1) },
                    onRename = { renaming = info.index; renameLabel = info.label },
                    onDelete = { onDeleteLayer(info.index) },
                    onMoveSelectionHere = { onMoveSelectionToLayer(info.index) },
                )
            }
            DropdownMenuItem(
                text = { Text("Add layer") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddLayer() },
            )
        }
    }
    if (renaming >= 0) {
        LayerRenameDialog(
            initial = renameLabel,
            onConfirm = { name -> onRenameLayer(renaming, name); renaming = -1 },
            onDismiss = { renaming = -1 },
        )
    }
}

/** One layer row: an active-dot + name (tap to activate), then visibility / up / down / rename / delete. */
@Composable
private fun LayerRow(
    info: LayerInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    hasSelection: Boolean,
    onActivate: () -> Unit,
    onToggleHidden: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveSelectionHere: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onActivate) {
            Icon(
                if (info.active) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (info.active) "Active layer" else "Make active",
                tint = if (info.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(info.label, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onToggleHidden, modifier = Modifier.size(32.dp)) {
            Icon(
                if (info.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (info.visible) "Hide layer" else "Show layer",
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
        }
        if (hasSelection) {
            IconButton(onClick = onMoveSelectionHere, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.HighlightAlt, contentDescription = "Move selection here")
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename layer")
        }
        IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete layer")
        }
    }
}

/** A dialog to rename a layer (blank clears the custom name). */
@Composable
private fun LayerRenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename layer") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
