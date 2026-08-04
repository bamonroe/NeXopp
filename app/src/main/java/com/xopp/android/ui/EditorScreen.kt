package com.xopp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.EraserMode
import com.xopp.android.render.GuideKind
import com.xopp.android.render.ImportPdfMode
import com.xopp.android.render.InputSettings
import com.xopp.android.render.PlaceKind
import com.xopp.android.render.Placement
import com.xopp.android.render.ShapeKind

/**
 * Push an [EditorTool] onto the surface: the three drawing tools set [Tool]; Hand toggles pan mode;
 * the authoring tools (text/image/LaTeX) set the surface's [DrawingSurfaceView.placeKind] so a tap
 * places that element instead of drawing.
 *
 * Two tools are variants of another rather than modes of their own: LASSO_SELECT is SELECT with a
 * freehand marquee, and ERASER_WHOLE is ERASER deleting whole strokes. Picking the tool is what sets
 * the variant, which is why neither has a separate mode menu.
 */
fun DrawingSurfaceView.applyTool(tool: EditorTool) {
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
fun DrawingSurfaceView.applySettings(s: AppSettings) {
    inputSettings = InputSettings(fingerDraws = s.fingerDraws, barrelAction = s.barrelAction)
    showHover = s.showHover
    pressureGamma = s.sensitivity.gamma
    strokePrecision = s.strokePrecision
    recognizeShapes = s.recognizeShapes
    setColumns(s.pageColumns)
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
 * rendering (see `docs/architecture.md`).
 *
 * The screen owns no loose state of its own: everything it remembers lives in [EditorUiState] (the
 * chrome) and [PaneState] (each canvas's mirror), and each region below — top bar, toolbar, pane,
 * overlays — is its own composable reading just the parts it needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: (filename: String, format: SaveFormat) -> Unit,
    currentSaveFormat: () -> SaveFormat,
    onImportPdf: (ImportPdfMode) -> Unit,
    onExportPdf: () -> Unit,
    onPickImage: (Placement) -> Unit,
    /** A pane's canvas has just been built: its index, then the view. */
    onSurfaceCreated: (Int, DrawingSurfaceView) -> Unit,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    audio: AudioUiState = AudioUiState(),
    /** One tab session per pane, in pane order (see [com.xopp.android.panes.EditorPane]). */
    tabs: List<TabsUiState> = listOf(TabsUiState()),
    /** Whether both panes are shown side by side. */
    splitView: Boolean = false,
    onToggleSplitView: () -> Unit = {},
    /** Which pane the chrome drives; touching a pane's canvas makes it the active one. */
    activePane: Int = 0,
    onActivePane: (Int) -> Unit = {},
    /** A document transfer in flight (label shown), or null. Remote files can take a while. */
    busy: String? = null,
) {
    val ui = rememberEditorUiState(settings)
    val pane = ui.pane(activePane)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (!ui.fullPage) {
                    EditorTopBar(
                        ui = ui,
                        pane = pane,
                        onOpen = onOpen,
                        onNewTab = { tabs[activePane.coerceIn(tabs.indices)].onNew() },
                        onSave = onSave,
                        onExportPdf = onExportPdf,
                        splitView = splitView,
                        onToggleSplitView = onToggleSplitView,
                    )
                }
            },
        ) { padding ->
            EditorBody(
                ui = ui,
                pane = pane,
                settings = settings,
                onSettingsChange = onSettingsChange,
                audio = audio,
                tabs = tabs,
                splitView = splitView,
                onActivePane = onActivePane,
                onSurfaceCreated = onSurfaceCreated,
                onPickImage = onPickImage,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }

        // Re-apply settings to the live surface whenever the user changes them in Settings.
        LaunchedEffect(settings) { ui.panes.forEach { it.surface?.applySettings(settings) } }

        // Settings is overlaid on top of the still-composed editor rather than replacing it, so the
        // AndroidView-hosted DrawingSurfaceView is never detached — the drawing (and undo history)
        // survives the round trip to Settings and back.
        if (ui.showSettings) {
            SettingsScreen(
                settings = settings,
                onChange = onSettingsChange,
                onBack = { ui.showSettings = false },
            )
        }

        // A document is moving to or from storage — possibly a slow network share, so say so and
        // swallow taps until it lands rather than letting the canvas be edited mid-transfer.
        if (busy != null) TransferOverlay(busy)

        EditorOverlays(
            ui = ui,
            pane = pane,
            settings = settings,
            onSettingsChange = onSettingsChange,
            currentSaveFormat = currentSaveFormat,
            onSaveAs = onSaveAs,
            onImportPdf = onImportPdf,
        )
    }
}

/**
 * The rail and the drawing area, laid out per [AppSettings.toolbarPosition] — the rail on any of the
 * four edges, the canvas taking the rest. In full-page mode the rail is simply absent and the canvas
 * has the lot.
 */
@Composable
private fun EditorBody(
    ui: EditorUiState,
    pane: PaneState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    audio: AudioUiState,
    tabs: List<TabsUiState>,
    splitView: Boolean,
    onActivePane: (Int) -> Unit,
    onSurfaceCreated: (Int, DrawingSurfaceView) -> Unit,
    onPickImage: (Placement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolbar: @Composable () -> Unit = {
        if (!ui.fullPage) {
            EditorToolbar(
                ui = ui,
                pane = pane,
                settings = settings,
                onSettingsChange = onSettingsChange,
                audio = audio,
            )
        }
    }
    val paneAt: @Composable (Int, Modifier) -> Unit = { index, paneModifier ->
        EditorPaneView(
            index = index,
            ui = ui,
            settings = settings,
            tabs = tabs,
            onActivePane = onActivePane,
            onSurfaceCreated = onSurfaceCreated,
            onPickImage = onPickImage,
            modifier = paneModifier,
        )
    }
    /** The drawing area: one pane, or both with a draggable bar between them. */
    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        if (splitView) {
            SplitLayout(
                fraction = ui.splitFraction,
                onFraction = { ui.splitFraction = it },
                modifier = canvasModifier,
                first = { paneAt(0, it) },
                second = { paneAt(1, it) },
            )
        } else {
            paneAt(0, canvasModifier)
        }
    }
    when (settings.toolbarPosition) {
        ToolbarPosition.LEFT -> Row(modifier = modifier) {
            toolbar(); canvas(Modifier.fillMaxHeight().weight(1f))
        }
        ToolbarPosition.RIGHT -> Row(modifier = modifier) {
            canvas(Modifier.fillMaxHeight().weight(1f)); toolbar()
        }
        ToolbarPosition.TOP -> Column(modifier = modifier) {
            toolbar(); canvas(Modifier.fillMaxWidth().weight(1f))
        }
        ToolbarPosition.BOTTOM -> Column(modifier = modifier) {
            canvas(Modifier.fillMaxWidth().weight(1f)); toolbar()
        }
    }
}
