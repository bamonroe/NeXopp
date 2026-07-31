package com.xopp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PlaceKind
import com.xopp.android.render.Placement

/**
 * Push an [EditorTool] onto the surface: the three drawing tools set [Tool]; Hand toggles pan mode;
 * the authoring tools (text/image/LaTeX) set the surface's [DrawingSurfaceView.placeKind] so a tap
 * places that element instead of drawing.
 */
private fun DrawingSurfaceView.applyTool(tool: EditorTool) {
    handMode = tool == EditorTool.HAND
    selectMode = tool == EditorTool.SELECT
    placeKind = when (tool) {
        EditorTool.TEXT -> PlaceKind.TEXT
        EditorTool.IMAGE -> PlaceKind.IMAGE
        EditorTool.TEXIMAGE -> PlaceKind.TEX
        else -> null
    }
    when (tool) {
        EditorTool.PEN -> this.tool = Tool.PEN
        EditorTool.HIGHLIGHTER -> this.tool = Tool.HIGHLIGHTER
        EditorTool.ERASER -> this.tool = Tool.ERASER
        else -> Unit // Hand / authoring tools keep the last drawing tool for when they're turned off
    }
}

/**
 * The single editor screen: a Material 3 top bar (undo/redo plus an overflow menu for open/save/
 * settings), a vertical control [SideToolbar] down the left edge, and the stylus canvas filling the
 * rest. The canvas is a classic [DrawingSurfaceView] hosted via [AndroidView] for low-latency stylus
 * rendering (see `docs/architecture.md`). Authoring taps raise [DrawingSurfaceView.onPlace], which
 * this screen turns into a text/LaTeX dialog or (for images) an [onPickImage] callback up to the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onPickImage: (Placement) -> Unit,
    onSurfaceCreated: (DrawingSurfaceView) -> Unit,
) {
    var tool by remember { mutableStateOf(EditorTool.PEN) }
    var color by remember { mutableStateOf(PEN_COLORS.first()) }
    var width by remember { mutableStateOf(PEN_WIDTHS[1].pt) }
    var zoom by remember { mutableStateOf(1f) }
    var pageCount by remember { mutableStateOf(1) }
    var currentPage by remember { mutableStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<DrawingSurfaceView?>(null) }
    var textPlacement by remember { mutableStateOf<Placement?>(null) }
    var texPlacement by remember { mutableStateOf<Placement?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Xopp") },
                actions = {
                    IconButton(onClick = { surface?.undo() }, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { surface?.redo() }, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    OverflowMenu(
                        onOpen = onOpen,
                        onSave = onSave,
                        onImportPdf = onImportPdf,
                        onExportPdf = onExportPdf,
                        onSettings = { showSettings = true },
                    )
                },
            )
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            SideToolbar(
                tool = tool,
                onTool = { tool = it; surface?.applyTool(it) },
                color = color,
                onColor = { color = it; surface?.colorArgb = it },
                width = width,
                onWidth = { width = it; surface?.baseWidthPt = it },
                zoom = zoom,
                onZoomIn = { surface?.zoomIn() },
                onZoomOut = { surface?.zoomOut() },
                onZoomReset = { surface?.resetZoom() },
                pageCount = pageCount,
                currentPage = currentPage,
                onAddPage = { surface?.addPage() },
                onRemovePage = { surface?.removePage() },
                onGoToPage = { surface?.goToPage(it) },
            )
            AndroidView(
                factory = { ctx ->
                    DrawingSurfaceView(ctx).also {
                        it.applyTool(tool)
                        it.colorArgb = color
                        it.baseWidthPt = width
                        it.onHistoryChanged = { u, r -> canUndo = u; canRedo = r }
                        it.onZoomChanged = { z -> zoom = z }
                        it.onPageCountChanged = { n -> pageCount = n }
                        it.onCurrentPageChanged = { p -> currentPage = p }
                        it.onSelectionChanged = { s -> hasSelection = s }
                        it.onPlace = { kind, placement ->
                            when (kind) {
                                PlaceKind.TEXT -> textPlacement = placement
                                PlaceKind.TEX -> texPlacement = placement
                                PlaceKind.IMAGE -> onPickImage(placement)
                            }
                        }
                        surface = it
                        onSurfaceCreated(it)
                    }
                },
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }
    }

        // Settings is overlaid on top of the still-composed editor rather than replacing it, so the
        // AndroidView-hosted DrawingSurfaceView is never detached — the drawing (and undo history)
        // survives the round trip to Settings and back.
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false })
        }

        // Contextual actions for the Select tool: shown only while something is selected.
        if (hasSelection) {
            SelectionActionBar(
                onDelete = { surface?.deleteSelection() },
                onDeselect = { surface?.clearSelection() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }

        textPlacement?.let { p ->
            TextInputDialog(
                title = if (p.existingText != null) "Edit text" else "Add text",
                initial = p.existingText ?: "",
                confirmLabel = "Save",
                onConfirm = { content -> surface?.insertText(p, content, TEXT_SIZE_PT, color); textPlacement = null },
                onDismiss = { surface?.cancelTextEdit(); textPlacement = null },
            )
        }
        texPlacement?.let { p ->
            TextInputDialog(
                title = "LaTeX",
                initial = "",
                confirmLabel = "Place",
                onConfirm = { latex -> surface?.insertTex(p, latex, color); texPlacement = null },
                onDismiss = { texPlacement = null },
            )
        }
    }
}

/**
 * The Select tool's contextual action bar: a small floating pill offering Delete and Deselect,
 * shown only while a selection is active (see [DrawingSurfaceView.onSelectionChanged]).
 */
@Composable
private fun SelectionActionBar(
    onDelete: () -> Unit,
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
        ) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Delete")
            }
            TextButton(onClick = onDeselect) { Text("Deselect") }
        }
    }
}

/** A minimal single/multi-line text-entry dialog used for both text boxes and LaTeX source. */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = false,
                label = { Text(title) },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Default point size for a newly authored text box. */
private const val TEXT_SIZE_PT = 12.0

/** The top-bar overflow ("hamburger") menu: open, save, and the settings page. */
@Composable
private fun OverflowMenu(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.Menu, contentDescription = "Menu")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Open") },
            leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
            onClick = { open = false; onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Import PDF") },
            leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
            onClick = { open = false; onImportPdf() },
        )
        DropdownMenuItem(
            text = { Text("Export PDF") },
            leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
            onClick = { open = false; onExportPdf() },
        )
        DropdownMenuItem(
            text = { Text("Save") },
            leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
            onClick = { open = false; onSave() },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { open = false; onSettings() },
        )
    }
}
