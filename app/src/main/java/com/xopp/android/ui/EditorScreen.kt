package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import com.xopp.android.format.FontDescription
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.GuideKind
import com.xopp.android.render.EraserMode
import com.xopp.android.render.InputSettings
import com.xopp.android.render.LayerInfo
import com.xopp.android.render.PlaceKind
import com.xopp.android.render.Placement
import com.xopp.android.render.ShapeKind
import com.xopp.android.render.StrokePrecision
import com.xopp.android.ui.theme.rememberCanvasChromeColors
import kotlin.math.roundToInt

/**
 * Push an [EditorTool] onto the surface: the three drawing tools set [Tool]; Hand toggles pan mode;
 * the authoring tools (text/image/LaTeX) set the surface's [DrawingSurfaceView.placeKind] so a tap
 * places that element instead of drawing.
 *
 * Two tools are variants of another rather than modes of their own: LASSO_SELECT is SELECT with a
 * freehand marquee, and ERASER_WHOLE is ERASER deleting whole strokes. Picking the tool is what sets
 * the variant, which is why neither has a separate mode menu.
 */
private fun DrawingSurfaceView.applyTool(tool: EditorTool) {
    handMode = tool == EditorTool.HAND
    selectMode = tool == EditorTool.SELECT || tool == EditorTool.LASSO_SELECT
    lassoMode = tool == EditorTool.LASSO_SELECT
    textSelectMode = tool == EditorTool.TEXT_SELECT
    verticalSpaceMode = tool == EditorTool.VERTICAL_SPACE
    audioPlayMode = tool == EditorTool.PLAY_OBJECT
    placeKind = when (tool) {
        EditorTool.TEXT -> PlaceKind.TEXT
        EditorTool.IMAGE -> PlaceKind.IMAGE
        EditorTool.TEXIMAGE -> PlaceKind.TEX
        else -> null
    }
    shapeKind = when (tool) {
        EditorTool.LINE -> ShapeKind.LINE
        EditorTool.ARROW -> ShapeKind.ARROW
        EditorTool.DOUBLE_ARROW -> ShapeKind.DOUBLE_ARROW
        EditorTool.COORDINATE_AXIS -> ShapeKind.COORDINATE_AXIS
        EditorTool.RECTANGLE -> ShapeKind.RECTANGLE
        EditorTool.ELLIPSE -> ShapeKind.ELLIPSE
        EditorTool.SPLINE -> ShapeKind.SPLINE
        else -> null
    }
    if (tool == EditorTool.ERASER || tool == EditorTool.ERASER_WHOLE) {
        eraserMode =
            if (tool == EditorTool.ERASER_WHOLE) EraserMode.WHOLE_STROKE else EraserMode.STANDARD
    }
    when (tool) {
        EditorTool.PEN -> this.tool = Tool.PEN
        EditorTool.HIGHLIGHTER -> this.tool = Tool.HIGHLIGHTER
        EditorTool.ERASER, EditorTool.ERASER_WHOLE -> this.tool = Tool.ERASER
        // Shapes are drawn as ordinary pen strokes; the shapeKind above turns a drag into geometry.
        EditorTool.LINE, EditorTool.ARROW, EditorTool.DOUBLE_ARROW, EditorTool.COORDINATE_AXIS,
        EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.SPLINE,
        -> this.tool = Tool.PEN
        else -> Unit // Hand / authoring tools keep the last drawing tool for when they're turned off
    }
}

/** Push the stylus/input [AppSettings] onto the surface (classifier settings, hover, pressure feel). */
private fun DrawingSurfaceView.applySettings(s: AppSettings) {
    inputSettings = InputSettings(fingerDraws = s.fingerDraws, barrelAction = s.barrelAction)
    showHover = s.showHover
    pressureGamma = s.sensitivity.gamma
    strokePrecision = s.strokePrecision
    recognizeShapes = s.recognizeShapes
    snapToGrid = s.snapToGrid
    snapRotation = s.snapRotation
    // Only place a guide the surface isn't already showing — re-placing on every settings change
    // would yank a guide the user has carefully positioned back to the middle of the screen.
    if (s.guideKind == GuideKind.NONE) {
        if (guide != null) placeGuide(GuideKind.NONE)
    } else if (guide == null) {
        placeGuide(s.guideKind)
    }
    flingStrength = s.momentum
    momentumCurve = s.momentumCurve
    panSensitivity = s.panSensitivity
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
    onSaveAs: (filename: String, format: SaveFormat) -> Unit,
    currentSaveFormat: () -> SaveFormat,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onPickImage: (Placement) -> Unit,
    onSurfaceCreated: (DrawingSurfaceView) -> Unit,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    audio: AudioUiState = AudioUiState(),
) {
    var tool by remember {
        mutableStateOf(startingTool(settings.defaultTool, settings.toolGroupSelections))
    }
    var color by remember { mutableStateOf(settings.lastColor) }
    var width by remember { mutableStateOf(settings.lastWidth) }
    var zoom by remember { mutableStateOf(1f) }
    var pageCount by remember { mutableStateOf(1) }
    var currentPage by remember { mutableStateOf(0) }
    // Vertical scroll geometry (content px) fed from the surface, driving the right-edge scroll thumb.
    var scrollY by remember { mutableStateOf(0f) }
    var contentHeight by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    // Full-page (immersive) view: a Hand-tool centre double-tap hides the top bar and side toolbar.
    var fullPage by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    var hasTextSelection by remember { mutableStateOf(false) }
    var hasClipboard by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<DrawingSurfaceView?>(null) }
    var textPlacement by remember { mutableStateOf<Placement?>(null) }
    var texPlacement by remember { mutableStateOf<Placement?>(null) }
    // Remembered defaults for a newly authored text box (an edit seeds from the element instead).
    var textFamily by remember { mutableStateOf(FontDescription.DEFAULT_FAMILY) }
    var textBold by remember { mutableStateOf(false) }
    var textItalic by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(TEXT_SIZE_PT) }
    var textColor by remember { mutableStateOf(PEN_COLORS.first()) }
    var lineStyle by remember { mutableStateOf(LineStyle.PLAIN) }
    val fill = if (settings.fillEnabled) settings.fillAlpha else null
    var layers by remember { mutableStateOf<List<LayerInfo>>(emptyList()) }
    var backgroundStyle by remember { mutableStateOf<String?>(null) }
    var pageSize by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            if (!fullPage) {
                TopAppBar(
                    // No title and a compact height: the bar is just the action row, so it eats as
                    // little of the drawing area as possible.
                    title = {},
                    modifier = Modifier.height(40.dp),
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
                            onSaveAs = { showSaveAs = true },
                            onImportPdf = onImportPdf,
                            onExportPdf = onExportPdf,
                            onSettings = { showSettings = true },
                        )
                    },
                )
            }
        },
    ) { padding ->
        val toolbar: @Composable () -> Unit = {
            if (!fullPage) {
            SideToolbar(
                horizontal = settings.toolbarPosition.isHorizontal,
                tool = tool,
                onTool = { tool = it; surface?.applyTool(it) },
                audio = audio,
                railOrder = settings.railOrder,
                railHidden = settings.railHidden,
                toolGroupSelections = settings.toolGroupSelections,
                onToolGroupSelections = { onSettingsChange(settings.copy(toolGroupSelections = it)) },
                color = color,
                onColor = { color = it; surface?.colorArgb = it; onSettingsChange(settings.withColorUsed(it)) },
                customColor = settings.customColor,
                recentColors = settings.recentColors,
                onRedefineCustom = { newColor ->
                    val old = settings.customColor
                    onSettingsChange(settings.copy(customColor = newColor))
                    // Keep the canvas in sync if the custom colour was the one currently selected.
                    if (color == old) {
                        color = newColor
                        surface?.colorArgb = newColor
                        onSettingsChange(settings.copy(customColor = newColor).withColorUsed(newColor))
                    }
                },
                width = width,
                onWidth = { width = it; surface?.baseWidthPt = it; onSettingsChange(settings.copy(lastWidth = it)) },
                widthSlots = settings.penWidths,
                onRedefineSlot = { i, newPt ->
                    val old = settings.penWidths[i]
                    val slots = settings.penWidths.toMutableList().also { it[i] = newPt }
                    // Keep the canvas in sync if the slot being resized is the one currently selected.
                    val active = width == old
                    onSettingsChange(
                        settings.copy(penWidths = slots, lastWidth = if (active) newPt else settings.lastWidth)
                    )
                    if (active) { width = newPt; surface?.baseWidthPt = newPt }
                },
                lineStyle = lineStyle,
                onLineStyle = { lineStyle = it; surface?.currentLineStyle = it },
                fill = fill,
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
                layers = layers,
                hasSelection = hasSelection,
                onAddLayer = { surface?.addLayer() },
                onDeleteLayer = { surface?.deleteLayer(it) },
                onRenameLayer = { i, name -> surface?.renameLayer(i, name) },
                onMoveLayer = { from, to -> surface?.moveLayer(from, to) },
                onActivateLayer = { surface?.setActiveLayer(it) },
                onToggleLayerHidden = { i, visible -> surface?.setLayerHidden(i, visible) },
                onMoveSelectionToLayer = { surface?.moveSelectionToLayer(it) },
                zoom = zoom,
                onZoomIn = { surface?.zoomIn() },
                onZoomOut = { surface?.zoomOut() },
                onZoomReset = { surface?.resetZoom() },
                pageCount = pageCount,
                currentPage = currentPage,
                onAddPage = { surface?.addPage() },
                onRemovePage = { surface?.removePage() },
                onGoToPage = { surface?.goToPage(it) },
                backgroundStyle = backgroundStyle,
                onBackgroundStyle = { surface?.setPageBackgroundStyle(it) },
                pageSize = pageSize,
                onPageSize = { w, h -> surface?.setPageSize(w, h) },
            )
            }
        }
        val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
            // The canvas lives outside the Compose tree, so its chrome colours are pushed in.
            val chrome = rememberCanvasChromeColors()
            Box(modifier = canvasModifier) {
            AndroidView(
                factory = { ctx ->
                    DrawingSurfaceView(ctx).also {
                        it.applyTool(tool)
                        it.applySettings(settings)
                        it.colorArgb = color
                        it.baseWidthPt = width
                        it.currentLineStyle = lineStyle
                        it.currentFill = fill
                        it.onLayersChanged = {
                            layers = it.visibleLayers()
                            backgroundStyle = it.visiblePageBackgroundStyle()
                            pageSize = it.visiblePageSize()
                        }
                        layers = it.visibleLayers()
                        backgroundStyle = it.visiblePageBackgroundStyle()
                        pageSize = it.visiblePageSize()
                        it.onHistoryChanged = { u, r -> canUndo = u; canRedo = r }
                        it.onZoomChanged = { z -> zoom = z }
                        it.onPageCountChanged = { n -> pageCount = n }
                        it.onCurrentPageChanged = { p -> currentPage = p; backgroundStyle = it.visiblePageBackgroundStyle(); pageSize = it.visiblePageSize() }
                        it.onScrollChanged = { y, total, vp -> scrollY = y; contentHeight = total; viewportHeight = vp }
                        it.onSelectionChanged = { s -> hasSelection = s }
                        it.onTextSelectionChanged = { s -> hasTextSelection = s }
                        it.onClipboardChanged = { c -> hasClipboard = c }
                        it.onToggleFullPage = { fullPage = !fullPage }
                        it.onPlace = { kind, placement ->
                            when (kind) {
                                PlaceKind.TEXT -> textPlacement = placement
                                PlaceKind.TEX -> texPlacement = placement
                                PlaceKind.IMAGE -> onPickImage(placement)
                            }
                        }
                        // Restore the pen the user left off with (the view's own defaults are fixed).
                        it.colorArgb = color
                        it.baseWidthPt = width
                        surface = it
                        onSurfaceCreated(it)
                    }
                },
                update = { it.applyChromeColors(chrome.backdrop, chrome.selection, chrome.guide) },
                modifier = Modifier.fillMaxSize(),
            )
            ScrollThumb(
                scrollY = scrollY,
                totalHeightPx = contentHeight,
                viewportPx = viewportHeight,
                currentPage = currentPage,
                pageCount = pageCount,
                onScrollTo = { surface?.scrollToY(it) },
                modifier = Modifier.matchParentSize(),
            )
            }
        }
        when (settings.toolbarPosition) {
            ToolbarPosition.LEFT -> Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                toolbar(); canvas(Modifier.fillMaxHeight().weight(1f))
            }
            ToolbarPosition.RIGHT -> Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                canvas(Modifier.fillMaxHeight().weight(1f)); toolbar()
            }
            ToolbarPosition.TOP -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                toolbar(); canvas(Modifier.fillMaxWidth().weight(1f))
            }
            ToolbarPosition.BOTTOM -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                canvas(Modifier.fillMaxWidth().weight(1f)); toolbar()
            }
        }
    }

        // Re-apply settings to the live surface whenever the user changes them in Settings.
        LaunchedEffect(settings) { surface?.applySettings(settings) }

        // Settings is overlaid on top of the still-composed editor rather than replacing it, so the
        // AndroidView-hosted DrawingSurfaceView is never detached — the drawing (and undo history)
        // survives the round trip to Settings and back.
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                onChange = onSettingsChange,
                onBack = { showSettings = false },
            )
        }

        // Contextual actions for the Select tools: the full action bar while something is selected,
        // otherwise (in a marquee mode) a small bar offering the clipboard.
        if (hasSelection) {
            SelectionActionBar(
                onCut = { surface?.cutSelection() },
                onCopy = { surface?.copySelection() },
                onDuplicate = { surface?.duplicateSelection() },
                onDelete = { surface?.deleteSelection() },
                onRecolor = { c -> surface?.restyleSelection(c, null) },
                onReWidth = { w -> surface?.restyleSelection(null, w.toDouble()) },
                widthSlots = settings.penWidths,
                onDeselect = { surface?.clearSelection() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        } else if (tool == EditorTool.SELECT || tool == EditorTool.LASSO_SELECT) {
            SelectModeBar(
                canPaste = hasClipboard,
                onPaste = { surface?.pasteClipboard() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }

        // Copy affordance for a PDF-text selection (the text-select tool).
        if (hasTextSelection) {
            TextSelectionBar(
                onCopy = { surface?.copyTextSelection() },
                onDeselect = { surface?.clearTextSelection() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }

        textPlacement?.let { p ->
            val existing = p.existing
            val seedFont = existing?.let { FontDescription.parse(it.font) }
            TextBoxDialog(
                title = if (existing != null) "Edit text" else "Add text",
                initialContent = existing?.content ?: "",
                initialFamily = seedFont?.family ?: textFamily,
                initialBold = seedFont?.bold ?: textBold,
                initialItalic = seedFont?.italic ?: textItalic,
                initialSize = existing?.size ?: textSize,
                initialColor = existing?.color ?: textColor,
                onConfirm = { content, family, bold, italic, sizePt, colorArgb ->
                    surface?.insertText(p, content, FontDescription(family, bold, italic).compose(), sizePt, colorArgb)
                    if (existing == null) {
                        textFamily = family; textBold = bold; textItalic = italic
                        textSize = sizePt; textColor = colorArgb
                    }
                    textPlacement = null
                },
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
        if (showSaveAs) {
            SaveAsDialog(
                initialFormat = currentSaveFormat(),
                onConfirm = { filename, format -> showSaveAs = false; onSaveAs(filename, format) },
                onDismiss = { showSaveAs = false },
            )
        }
    }
}

/**
 * The Select tool's contextual action bar, shown while a selection is active: cut / copy /
 * duplicate / delete, recolour and re-width the selected strokes, and deselect. Horizontally
 * scrollable so it fits narrow screens. (Resize and rotate are on-canvas handles, not buttons.)
 */
@Composable
private fun SelectionActionBar(
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRecolor: (Int) -> Unit,
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
            RecolorMenu(onRecolor)
            ReWidthMenu(widthSlots, onReWidth)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            TextButton(onClick = onDeselect) { Text("Done") }
        }
    }
}

/** A palette drop-down that recolours the selection. */
@Composable
private fun RecolorMenu(onRecolor: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Palette, contentDescription = "Recolour") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (c in PEN_COLORS) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { onRecolor(c); open = false },
                    )
                }
            }
        }
    }
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
 * Shown in a marquee mode when nothing is selected: paste the clipboard onto the visible page. The
 * marquee shape isn't picked here — rectangle and lasso are separate rail tools (see [EditorTool]) —
 * so the bar composes to nothing when there's nothing on the clipboard.
 */
@Composable
private fun SelectModeBar(
    canPaste: Boolean,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canPaste) return
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
            TextButton(onClick = onPaste) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
        }
    }
}

/** Shown while PDF text is selected: copy the selection to the system clipboard, or deselect. */
@Composable
private fun TextSelectionBar(
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

/**
 * "Save As" chooser: name the file and pick the on-disk format. [SaveFormat.ORIGINAL] writes the
 * standard gzip `.xopp` (a PDF background stays linked by location); [SaveFormat.ZIPPED] writes a
 * single self-contained file with the PDF embedded inside (see `docs/architecture.md`). The choice
 * becomes sticky — later plain Saves reuse it — so the picker pre-selects the current format.
 */
@Composable
private fun SaveAsDialog(
    initialFormat: SaveFormat,
    onConfirm: (filename: String, format: SaveFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("document.xopp") }
    var format by remember { mutableStateOf(initialFormat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save As") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Format", style = MaterialTheme.typography.labelMedium)
                FormatOption(
                    selected = format == SaveFormat.ORIGINAL,
                    title = "Original (gzip)",
                    subtitle = "Standard Xournal++ file; any PDF background stays linked by location.",
                    onClick = { format = SaveFormat.ORIGINAL },
                )
                FormatOption(
                    selected = format == SaveFormat.ZIPPED,
                    title = "Zipped (single file)",
                    subtitle = "One portable file with the PDF embedded inside.",
                    onClick = { format = SaveFormat.ZIPPED },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, format) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One selectable format row in [SaveAsDialog]: a radio plus a title and one-line explanation. */
@Composable
private fun FormatOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Default point size for a newly authored text box, and the slider bounds for editing it. */
private const val TEXT_SIZE_PT = 12.0
private const val TEXT_SIZE_MIN = 6f
private const val TEXT_SIZE_MAX = 96f

/** The families offered in the text dialog — names desktop Xournal++ and Android both resolve. */
private val TEXT_FAMILIES = listOf("Sans", "Serif", "Monospace")

/**
 * The styled text-box editor: content plus the styling the `.xopp` `<text>` element can hold —
 * font family, bold/italic, point size, and colour. Confirms with all five so the caller can
 * compose the font description and place/replace the box. (Underline is intentionally absent —
 * the format can't store it; see the scope rule in `CLAUDE.md`.)
 */
@Composable
private fun TextBoxDialog(
    title: String,
    initialContent: String,
    initialFamily: String,
    initialBold: Boolean,
    initialItalic: Boolean,
    initialSize: Double,
    initialColor: Int,
    onConfirm: (content: String, family: String, bold: Boolean, italic: Boolean, sizePt: Double, colorArgb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }
    var family by remember { mutableStateOf(initialFamily) }
    var bold by remember { mutableStateOf(initialBold) }
    var italic by remember { mutableStateOf(initialItalic) }
    var size by remember { mutableStateOf(initialSize.toFloat().coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)) }
    var colorArgb by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    singleLine = false,
                    label = { Text("Text") },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FontFamilyPicker(family = family, onFamily = { family = it })
                    FilterChip(selected = bold, onClick = { bold = !bold }, label = { Text("Bold") })
                    FilterChip(selected = italic, onClick = { italic = !italic }, label = { Text("Italic") })
                }
                Text("Size: ${size.roundToInt()} pt")
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = TEXT_SIZE_MIN..TEXT_SIZE_MAX,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in PEN_COLORS) TextSwatch(color = c, selected = c == colorArgb) { colorArgb = c }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content, family, bold, italic, size.toDouble(), colorArgb) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Dropdown to pick a text font family from [TEXT_FAMILIES]. */
@Composable
private fun FontFamilyPicker(family: String, onFamily: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(family) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (f in TEXT_FAMILIES) {
                DropdownMenuItem(text = { Text(f) }, onClick = { onFamily(f); open = false })
            }
        }
    }
}

/** A tappable colour circle for the text dialog (a local twin of the toolbar's swatch). */
@Composable
private fun TextSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** The top-bar overflow ("hamburger") menu: open, save, and the settings page. */
@Composable
private fun OverflowMenu(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
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
            text = { Text("Save As…") },
            leadingIcon = { Icon(Icons.Filled.SaveAs, contentDescription = null) },
            onClick = { open = false; onSaveAs() },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { open = false; onSettings() },
        )
    }
}
