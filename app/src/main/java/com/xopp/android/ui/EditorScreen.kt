package com.xopp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView

/** Push an [EditorTool] onto the surface: the three drawing tools set [Tool]; Hand toggles pan mode. */
private fun DrawingSurfaceView.applyTool(tool: EditorTool) {
    handMode = tool == EditorTool.HAND
    when (tool) {
        EditorTool.PEN -> this.tool = Tool.PEN
        EditorTool.HIGHLIGHTER -> this.tool = Tool.HIGHLIGHTER
        EditorTool.ERASER -> this.tool = Tool.ERASER
        EditorTool.HAND -> Unit // pan mode; keep the last drawing tool for when it's turned off
    }
}

/**
 * The single editor screen: a Material 3 top bar (undo/redo plus an overflow menu for open/save/
 * settings), the stylus canvas, and the bottom control bar. The canvas is a classic
 * [DrawingSurfaceView] hosted via [AndroidView] for low-latency stylus rendering (see
 * `docs/architecture.md` for why it isn't a Compose `Canvas`). Choosing Settings swaps the whole
 * screen for [SettingsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onSurfaceCreated: (DrawingSurfaceView) -> Unit,
) {
    var tool by remember { mutableStateOf(EditorTool.PEN) }
    var color by remember { mutableStateOf(PEN_COLORS.first()) }
    var width by remember { mutableStateOf(PEN_WIDTHS[1].pt) }
    var zoom by remember { mutableStateOf(1f) }
    var pageCount by remember { mutableStateOf(1) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<DrawingSurfaceView?>(null) }

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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    DrawingSurfaceView(ctx).also {
                        it.applyTool(tool)
                        it.colorArgb = color
                        it.baseWidthPt = width
                        it.onHistoryChanged = { u, r -> canUndo = u; canRedo = r }
                        it.onZoomChanged = { z -> zoom = z }
                        it.onPageCountChanged = { n -> pageCount = n }
                        surface = it
                        onSurfaceCreated(it)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            BottomToolbar(
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
                onAddPage = { surface?.addPage() },
                onRemovePage = { surface?.removePage() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

        // Settings is overlaid on top of the still-composed editor rather than replacing it, so the
        // AndroidView-hosted DrawingSurfaceView is never detached — the drawing (and undo history)
        // survives the round trip to Settings and back.
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false })
        }
    }
}

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
