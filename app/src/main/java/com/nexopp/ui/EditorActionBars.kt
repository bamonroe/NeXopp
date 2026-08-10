package com.nexopp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * The Select tool's contextual action bar, shown while a selection is active: cut / copy /
 * duplicate / delete, recolour and re-width the selected strokes, and deselect. Horizontally
 * scrollable so it fits narrow screens. (Resize and rotate are on-canvas handles, not buttons.)
 */
@Composable
fun SelectionActionBar(
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRecolor: (Int) -> Unit,
    palette: ColorPaletteState,
    onReWidth: (Float) -> Unit,
    widthSlots: List<Float>,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "Cut") }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.LibraryAdd, contentDescription = "Duplicate") }
            RecolorMenu(onRecolor, palette)
            ReWidthMenu(widthSlots, onReWidth)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            TextButton(onClick = onDeselect) { Text("Done") }
        }
    }
}

/**
 * A drop-down that recolours the selection, offering the shared [ColorPaletteRows] — the same
 * swatches, custom slot and recents as the pen's palette. The colour picked is recorded as used
 * (but not as the *pen's* colour: recolouring a selection doesn't change what the pen draws with).
 */
@Composable
private fun RecolorMenu(onRecolor: (Int) -> Unit, palette: ColorPaletteState) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Palette, contentDescription = "Recolour") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ColorPaletteRows(
                selected = null,
                palette = palette,
                onPick = { c -> onRecolor(c); palette.note(c); open = false },
                onEditCustom = { editing = true; open = false },
            )
        }
    }
    CustomColorEditor(visible = editing, palette = palette, onDismiss = { editing = false })
}

/** A width drop-down that re-widths the selected strokes, using the same configurable slots as the pen. */
@Composable
private fun ReWidthMenu(widthSlots: List<Float>, onReWidth: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.LineWeight, contentDescription = "Width") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            widthSlots.forEachIndexed { i, pt ->
                DropdownMenuItem(
                    text = { Text("${PEN_WIDTH_LABELS[i]}  (${ptLabel(pt)} pt)") },
                    onClick = { onReWidth(pt); open = false },
                )
            }
        }
    }
}

/**
 * Shown in a marquee mode when nothing is selected: paste the clipboard onto the visible page, and —
 * once a background-select marquee has been dragged — Copy or Cut the region it left behind. Copy
 * re-captures the region (so it can be re-copied after the clipboard has moved on) and Cut also
 * erases the ink it covers. The marquee shape isn't picked here — rectangle and lasso are separate
 * rail tools (see [EditorTool]) — so the bar composes to nothing when there is nothing to act on.
 */
@Composable
fun SelectModeBar(
    canPaste: Boolean,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    hasRegion: Boolean = false,
    onCopyRegion: () -> Unit = {},
    onCutRegion: () -> Unit = {},
    onClearRegion: () -> Unit = {},
) {
    if (!canPaste && !hasRegion) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasRegion) {
                TextButton(onClick = onCopyRegion) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                TextButton(onClick = onCutRegion) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Cut")
                }
            }
            if (canPaste) {
                TextButton(onClick = onPaste) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Paste")
                }
            }
            if (hasRegion) {
                IconButton(onClick = onClearRegion) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear region")
                }
            }
        }
    }
}

/**
 * Shown while a spline is open: finish the curve, drop the last control point, or throw it away.
 * The keyboard bindings (Enter/Escape) and the finishing double-tap still work — this is the
 * on-screen equivalent, since a tablet with no keyboard otherwise has only the double-tap, which is
 * easy to miss and awkward when two control points genuinely belong close together.
 *
 * Finish is disabled below two points, matching the commit rule: a one-node spline draws nothing.
 */
@Composable
fun SplineModeBar(
    nodeCount: Int,
    onFinish: () -> Unit,
    onUndoPoint: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nodeCount <= 0) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$nodeCount ${if (nodeCount == 1) "point" else "points"}")
            IconButton(onClick = onUndoPoint) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo last point")
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Discard curve")
            }
            TextButton(onClick = onFinish, enabled = nodeCount >= 2) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Finish")
            }
        }
    }
}

/** Shown while PDF text is selected: copy the selection to the system clipboard, or deselect. */
@Composable
fun TextSelectionBar(
    onCopy: () -> Unit,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            TextButton(onClick = onDeselect) { Text("Deselect") }
        }
    }
}
