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
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * selecting the active layer (where new ink lands), reordering (up/down z-order), merging into the
 * layer below, renaming, and deleting; plus "Add layer" and — when something is selected — "Move selection here".
 */
@Composable
internal fun LayersPopupButton(callbacks: ToolbarLayerCallbacks) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(-1) }
    var renameLabel by remember { mutableStateOf("") }
    ToolbarPopupButton(
        icon = Icons.Filled.Layers,
        contentDescription = "Layers",
    ) { dismiss ->
        MenuHeading("Layers (top first)")
        for (info in callbacks.layers.asReversed()) {
            LayerRow(
                info = info,
                canMoveUp = info.index < callbacks.layers.lastIndex,
                canMoveDown = info.index > 0,
                canDelete = callbacks.layers.size > 1,
                canMergeDown = info.index > 0,
                hasSelection = callbacks.hasSelection,
                onActivate = { callbacks.onActivateLayer(info.index); dismiss() },
                onToggleHidden = { callbacks.onToggleLayerHidden(info.index, info.visible) },
                onMoveLayer = { from, to -> callbacks.onMoveLayer(from, to) },
                onRename = { renaming = info.index; renameLabel = info.label },
                onDelete = { callbacks.onDeleteLayer(info.index) },
                onMergeDown = { callbacks.onMergeLayerDown(info.index) },
                onMoveSelectionHere = { callbacks.onMoveSelectionToLayer(info.index) },
            )
        }
        DropdownMenuItem(
            text = { Text("Add layer") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { callbacks.onAddLayer(); dismiss() },
        )
    }
    if (renaming >= 0) {
        RenameDialog(
            title = "Rename layer",
            label = "Layer name",
            initialValue = renameLabel,
            onConfirm = { name -> callbacks.onRenameLayer(renaming, name); renaming = -1 },
            onDismiss = { renaming = -1 },
        )
    }
}

/** One layer row: an active-dot + name (tap to activate), then visibility / merge / move-selection / rename, then reorder controls. */
@Composable
private fun LayerRow(
    info: LayerInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    canMergeDown: Boolean,
    hasSelection: Boolean,
    onActivate: () -> Unit,
    onToggleHidden: () -> Unit,
    onMoveLayer: (from: Int, to: Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit,
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
        IconButton(onClick = onMergeDown, enabled = canMergeDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Merge, contentDescription = "Merge layer down")
        }
        if (hasSelection) {
            IconButton(onClick = onMoveSelectionHere, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.HighlightAlt, contentDescription = "Move selection here")
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename layer")
        }
        ReorderControls(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            canDelete = canDelete,
            itemName = info.label,
            onMove = { delta -> onMoveLayer(info.index, info.index + delta) },
            onDelete = onDelete,
        )
    }
}
