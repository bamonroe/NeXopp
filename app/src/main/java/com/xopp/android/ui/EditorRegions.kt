package com.xopp.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xopp.android.render.BarrelDoubleAction
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PlaceKind
import com.xopp.android.render.Placement
import com.xopp.android.ui.theme.rememberCanvasChromeColors

/**
 * The editor's top bar: undo/redo for the active pane plus the overflow menu. No title and a compact
 * height — the bar is just the action row, so it eats as little of the drawing area as possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    ui: EditorUiState,
    pane: PaneState,
    onOpen: () -> Unit,
    onNewTab: () -> Unit,
    onSave: () -> Unit,
    onExportPdf: () -> Unit,
    splitView: Boolean,
    onToggleSplitView: () -> Unit,
) {
    TopAppBar(
        title = {},
        modifier = Modifier.height(40.dp),
        actions = {
            IconButton(onClick = { pane.surface?.undo() }, enabled = pane.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { pane.surface?.redo() }, enabled = pane.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            OverflowMenu(
                onOpen = onOpen,
                onNewTab = onNewTab,
                onSave = onSave,
                onSaveAs = { ui.showSaveAs = true },
                onImportPdf = { ui.showImportPdf = true },
                onExportPdf = onExportPdf,
                onSettings = { ui.showSettings = true },
                splitView = splitView,
                onToggleSplitView = onToggleSplitView,
            )
        },
    )
}

/**
 * The control rail, wired to the active pane's surface. Every callback either drives the canvas
 * directly or writes back through [onSettingsChange] so the choice is persisted.
 */
@Composable
fun EditorToolbar(
    ui: EditorUiState,
    pane: PaneState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    audio: AudioUiState,
) {
    val surface = pane.surface
    SideToolbar(
        horizontal = settings.toolbarPosition.isHorizontal,
        tool = ui.tool,
        onTool = { ui.tool = it; surface?.applyTool(it) },
        audio = audio,
        railOrder = settings.railOrder,
        railHidden = settings.railHidden,
        toolGroupSelections = settings.toolGroupSelections,
        onToolGroupSelections = { onSettingsChange(settings.copy(toolGroupSelections = it)) },
        color = ui.color,
        onColor = { ui.color = it; surface?.colorArgb = it; onSettingsChange(settings.withColorUsed(it)) },
        palette = rememberColorPaletteState(settings, onSettingsChange),
        onRedefineCustom = { newColor ->
            val old = settings.customColor
            onSettingsChange(settings.copy(customColor = newColor))
            // Keep the canvas in sync if the custom colour was the one currently selected.
            if (ui.color == old) {
                ui.color = newColor
                surface?.colorArgb = newColor
                onSettingsChange(settings.copy(customColor = newColor).withColorUsed(newColor))
            }
        },
        width = ui.width,
        onWidth = { ui.width = it; surface?.baseWidthPt = it; onSettingsChange(settings.copy(lastWidth = it)) },
        widthSlots = settings.penWidths,
        onRedefineSlot = { i, newPt ->
            val old = settings.penWidths[i]
            val slots = settings.penWidths.toMutableList().also { it[i] = newPt }
            // Keep the canvas in sync if the slot being resized is the one currently selected.
            val active = ui.width == old
            onSettingsChange(
                settings.copy(penWidths = slots, lastWidth = if (active) newPt else settings.lastWidth)
            )
            if (active) { ui.width = newPt; surface?.baseWidthPt = newPt }
        },
        lineStyle = ui.lineStyle,
        onLineStyle = { ui.lineStyle = it; surface?.currentLineStyle = it },
        fill = settings.currentFill,
        onFill = {
            surface?.currentFill = it
            onSettingsChange(
                settings.copy(fillEnabled = it != null, fillAlpha = it ?: settings.fillAlpha)
            )
        },
        recognizeShapes = settings.recognizeShapes,
        onRecognizeShapes = {
            surface?.recognizeShapes = it
            onSettingsChange(settings.copy(recognizeShapes = it))
        },
        guideKind = settings.guideKind,
        onGuideKind = {
            surface?.placeGuide(it)
            onSettingsChange(settings.copy(guideKind = it))
        },
        layers = pane.layers,
        hasSelection = pane.hasSelection,
        onAddLayer = { surface?.addLayer() },
        onDeleteLayer = { surface?.deleteLayer(it) },
        onMergeLayerDown = { surface?.mergeLayerDown(it) },
        onRenameLayer = { i, name -> surface?.renameLayer(i, name) },
        onMoveLayer = { from, to -> surface?.moveLayer(from, to) },
        onActivateLayer = { surface?.setActiveLayer(it) },
        onToggleLayerHidden = { i, visible -> surface?.setLayerHidden(i, visible) },
        onMoveSelectionToLayer = { surface?.moveSelectionToLayer(it) },
        zoom = pane.zoom,
        onZoomIn = { surface?.zoomIn() },
        onZoomOut = { surface?.zoomOut() },
        onZoomReset = { surface?.resetZoom() },
        pageCount = pane.pageCount,
        currentPage = pane.currentPage,
        onAddPage = { surface?.addPage() },
        onRemovePage = { surface?.removePage() },
        onGoToPage = { surface?.goToPage(it) },
        backgroundStyle = pane.backgroundStyle,
        onBackgroundStyle = { surface?.setPageBackgroundStyle(it) },
        pageSize = pane.pageSize,
        onPageSize = { w, h -> surface?.setPageSize(w, h) },
        pageColumns = settings.pageColumns,
        onPageColumns = {
            surface?.setColumns(it)
            onSettingsChange(settings.copy(pageColumns = it))
        },
        pagesEditMode = pane.pagesEditMode,
        onPagesEditMode = { pane.pagesEditMode = it; surface?.setPagesEditMode(it) },
        selectedPages = pane.selectedPages,
        onDeleteSelectedPages = { surface?.deleteSelectedPages() },
        onClearPageSelection = { surface?.clearPageSelection() },
        copiedPages = pane.copiedPages,
        onCopySelectedPages = { surface?.copySelectedPages() },
        onPastePages = { surface?.pasteCopiedPages() },
    )
}

/**
 * One pane: its tab strip over its canvas. All of the surface's callbacks write into *that* pane's
 * [PaneState], never the active one, so a background pane keeps its own page, zoom and undo state up
 * to date while the toolbar drives the other. A touch anywhere in the pane (observed on the initial
 * pass, so the canvas still gets the event) hands it focus.
 */
@Composable
fun EditorPaneView(
    index: Int,
    ui: EditorUiState,
    settings: AppSettings,
    tabs: List<TabsUiState>,
    onActivePane: (Int) -> Unit,
    onSurfaceCreated: (Int, DrawingSurfaceView) -> Unit,
    onPickImage: (Placement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = ui.panes[index]
    // The canvas lives outside the Compose tree, so its chrome colours are pushed in.
    val chrome = rememberCanvasChromeColors()
    Column(
        modifier = modifier.pointerInput(index) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onActivePane(index)
            }
        },
    ) {
        // Only drawn once a document is open (see [TabStrip]).
        if (!ui.fullPage) TabStrip(tabs[index.coerceIn(tabs.indices)], modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    DrawingSurfaceView(ctx).also {
                        it.applyTool(ui.tool)
                        it.applySettings(settings)
                        it.colorArgb = ui.color
                        it.baseWidthPt = ui.width
                        it.currentLineStyle = ui.lineStyle
                        it.currentFill = settings.currentFill
                        it.onLayersChanged = {
                            state.layers = it.visibleLayers()
                            state.backgroundStyle = it.visiblePageBackgroundStyle()
                            state.pageSize = it.visiblePageSize()
                        }
                        state.layers = it.visibleLayers()
                        state.backgroundStyle = it.visiblePageBackgroundStyle()
                        state.pageSize = it.visiblePageSize()
                        it.onHistoryChanged = { u, r -> state.canUndo = u; state.canRedo = r }
                        it.onZoomChanged = { z -> state.zoom = z }
                        it.onPageCountChanged = { n -> state.pageCount = n }
                        it.onPageSelectionChanged = { n -> state.selectedPages = n }
                        it.onPageClipboardChanged = { n -> state.copiedPages = n }
                        it.onCurrentPageChanged = { page -> state.currentPage = page; state.backgroundStyle = it.visiblePageBackgroundStyle(); state.pageSize = it.visiblePageSize() }
                        it.onScrollChanged = { y, total, vp -> state.scrollY = y; state.contentHeight = total; state.viewportHeight = vp }
                        it.onSelectionChanged = { s -> state.hasSelection = s }
                        it.onTextSelectionChanged = { s -> state.hasTextSelection = s }
                        it.onClipboardChanged = { c -> state.hasClipboard = c }
                        it.onToggleFullPage = { ui.fullPage = !ui.fullPage }
                        // Undo/redo are handled on the surface itself; these need the editor's tool
                        // state, and each flips back to the previous tool on a second double-click.
                        it.onBarrelDoubleClick = { action ->
                            when (action) {
                                BarrelDoubleAction.TOGGLE_ERASER -> ui.toggleTool(EditorTool.ERASER)
                                BarrelDoubleAction.TOGGLE_SELECT -> ui.toggleTool(EditorTool.SELECT)
                                BarrelDoubleAction.TOGGLE_FULL_PAGE -> ui.fullPage = !ui.fullPage
                                else -> Unit
                            }
                            it.applyTool(ui.tool)
                        }
                        it.onPlace = { kind, placement ->
                            onActivePane(index)
                            when (kind) {
                                PlaceKind.TEXT -> ui.textPlacement = placement
                                PlaceKind.TEX -> ui.texPlacement = placement
                                PlaceKind.IMAGE -> onPickImage(placement)
                            }
                        }
                        state.surface = it
                        onSurfaceCreated(index, it)
                    }
                },
                update = { it.applyChromeColors(chrome.backdrop, chrome.selection, chrome.guide) },
                modifier = Modifier.fillMaxSize(),
            )
            ScrollThumb(
                scrollY = state.scrollY,
                totalHeightPx = state.contentHeight,
                viewportPx = state.viewportHeight,
                currentPage = state.currentPage,
                pageCount = state.pageCount,
                onScrollTo = { state.surface?.scrollToY(it) },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** The top-bar overflow ("hamburger") menu: open, save, and the settings page. */
@Composable
private fun OverflowMenu(
    onOpen: () -> Unit,
    onNewTab: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onSettings: () -> Unit,
    splitView: Boolean,
    onToggleSplitView: () -> Unit,
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
            text = { Text("New document") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
            onClick = { open = false; onNewTab() },
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
            text = { Text("Save As…") },
            leadingIcon = { Icon(Icons.Filled.SaveAs, contentDescription = null) },
            onClick = { open = false; onSaveAs() },
        )
        DropdownMenuItem(
            text = { Text(if (splitView) "Close split view" else "Split view") },
            leadingIcon = { Icon(Icons.Filled.VerticalSplit, contentDescription = null) },
            onClick = { open = false; onToggleSplitView() },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { open = false; onSettings() },
        )
    }
}
