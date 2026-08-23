package com.nexopp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nexopp.format.ExportFormat
import com.nexopp.format.SaveFormat
import com.nexopp.format.model.Tool
import com.nexopp.render.DrawingSurfaceView
import com.nexopp.render.EraserMode
import com.nexopp.render.GuideKind
import com.nexopp.render.ImportPdfMode
import com.nexopp.render.InputSettings
import com.nexopp.render.PlaceKind
import com.nexopp.render.Placement
import com.nexopp.render.ShapeKind

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
    backgroundSelectMode = tool == EditorTool.BG_SELECT
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
    inputSettings = InputSettings(
        fingerDraws = s.fingerDraws,
        barrelAction = s.barrelAction,
        barrelDoubleAction = s.barrelDoubleAction,
        paletteInvocation = s.paletteInvocation,
    )
    showHover = s.showHover
    paletteHaptics = s.paletteHaptics
    paletteCloseOnSelect = s.paletteCloseOnSelect
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
    palette = s.radialPalette
    presetColors = s.presets.associate { it.id to it.colorArgb }
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
    /**
     * What the open document would lose if saved in the given format, one sentence per loss and
     * empty when nothing is lost. Drives the Save As confirmation; the screen never writes the text.
     */
    saveWarnings: (SaveFormat) -> List<String> = { emptyList() },
    /**
     * What a format crossing that has **already happened** cost, or null: a `.rnote` opened with
     * content we cannot express, or one saved with content it cannot hold. Both are reports rather
     * than questions — a plain Save of an already-lossy tab writes first and tells you after,
     * because the user chose that format once and a modal every time would train them to dismiss it.
     */
    notice: ContentNotice? = null,
    /** [notice] has been shown; clear it so the same one is not reported twice. */
    onNoticeShown: () -> Unit = {},
    onImportPdf: (ImportPdfMode) -> Unit,
    /**
     * Export confirmed in the Export dialog: the chosen format, the [com.nexopp.format.PageRange]
     * spec typed into the Pages field, the raster DPI, and the file-name stem. The screen doesn't
     * know which SAF picker that needs — the host resolves the spec and opens the right one.
     */
    onExport: (ExportFormat, String, Int, String) -> Unit,
    onPickImage: (Placement) -> Unit,
    /** A pane's canvas has just been built: its index, then the view. */
    onSurfaceCreated: (Int, DrawingSurfaceView) -> Unit,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    audio: AudioUiState = AudioUiState(),
    /** One tab session per pane, in pane order (see [com.nexopp.panes.EditorPane]). */
    tabs: List<TabsUiState> = listOf(TabsUiState()),
    /** Whether both panes are shown side by side. */
    splitView: Boolean = false,
    onToggleSplitView: () -> Unit = {},
    /** Which pane the chrome drives; touching a pane's canvas makes it the active one. */
    activePane: Int = 0,
    onActivePane: (Int) -> Unit = {},
    /** A document transfer in flight (label shown), or null. Remote files can take a while. */
    busy: String? = null,
    /** A quiet autosave is in flight: the top bar shows a small spinner, the canvas stays live. */
    saving: Boolean = false,
    /** Back was pressed with nothing left to dismiss: leave the app. */
    onExit: () -> Unit = {},
    /** The active tab has a file behind it, so the reload button is live rather than greyed out. */
    canReload: Boolean = false,
    /**
     * Reload confirmed: re-read the active tab from its file, throwing its unsaved edits away. The
     * screen only ever calls this after the confirmation dialog says yes.
     */
    onReload: () -> Unit = {},
    /** The top app bar content; defaults to [EditorTopBar]. */
    topBar: @Composable (EditorUiState, PaneState, TabsUiState) -> Unit = { ui, pane, tabs ->
        EditorTopBar(
            ui = ui,
            pane = pane,
            tabs = tabs,
            onOpen = onOpen,
            onNewTab = { tabs.onNew() },
            onSave = onSave,
            splitView = splitView,
            onToggleSplitView = onToggleSplitView,
            saving = saving,
            canReload = canReload,
        )
    },
    /** The rail/toolbar content; defaults to [EditorToolbar]. */
    paneChrome: @Composable (EditorUiState, PaneState, AppSettings, (AppSettings) -> Unit, AudioUiState) -> Unit = { ui, pane, settings, onSettingsChange, audio ->
        EditorToolbar(
            ui = ui,
            pane = pane,
            settings = settings,
            onSettingsChange = onSettingsChange,
            audio = audio,
        )
    },
) {
    val ui = rememberEditorUiState(settings)
    val pane = ui.pane(activePane)
    val snackbars = remember { SnackbarHostState() }

    // Back peels the editor's transient layers off one at a time before it ever exits.
    EditorBackHandler(ui = ui, pane = pane, busy = busy != null, onExit = onExit)

    // An open or a save that lost something: say so once, briefly, with the full list a tap away.
    LaunchedEffect(notice) {
        val pending = notice ?: return@LaunchedEffect
        // Consume *after* the snackbar closes, never before: clearing the state is what this
        // effect is keyed on, so an early call cancels the very coroutine that is showing it.
        val result = snackbars.showSnackbar(
            message = pending.message,
            actionLabel = "Details",
            duration = SnackbarDuration.Long,
        )
        onNoticeShown()
        if (result == SnackbarResult.ActionPerformed) ui.noticeDetails = pending
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (!ui.fullPage) {
                    topBar(ui, pane, tabs[activePane.coerceIn(tabs.indices)])
                }
            },
            snackbarHost = { SnackbarHost(snackbars) },
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
                paneChrome = paneChrome,
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
            saveWarnings = saveWarnings,
            onImportPdf = onImportPdf,
            onExport = onExport,
            onReload = onReload,
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
    paneChrome: @Composable (EditorUiState, PaneState, AppSettings, (AppSettings) -> Unit, AudioUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolbar: @Composable () -> Unit = {
        if (!ui.fullPage) {
            paneChrome(ui, pane, settings, onSettingsChange, audio)
        }
    }
    // A pane hosts an AndroidView-backed DrawingSurfaceView, and that view owns the pane's live
    // viewport (zoom, scroll) and undo history. Single-pane and split view put the pane in two
    // different composition positions, so a plain lambda would dispose the surface and build a
    // fresh one — at zoom 1.0, with an empty history — every time the split toggles. Movable
    // content moves the *same* subtree between the two slots instead. The arguments are read
    // through rememberUpdatedState so the remembered lambdas still see the latest values.
    val latest = rememberUpdatedState(
        PaneArgs(ui, settings, onSettingsChange, tabs, onActivePane, onSurfaceCreated, onPickImage),
    )
    val panes = remember {
        List(2) { index ->
            movableContentOf<Modifier> { paneModifier ->
                val args = latest.value
                EditorPaneView(
                    index = index,
                    ui = args.ui,
                    settings = args.settings,
                    onSettingsChange = args.onSettingsChange,
                    tabs = args.tabs,
                    onActivePane = args.onActivePane,
                    onSurfaceCreated = args.onSurfaceCreated,
                    onPickImage = args.onPickImage,
                    modifier = paneModifier,
                )
            }
        }
    }
    /** The drawing area: one pane, or both with a draggable bar between them. */
    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        if (splitView) {
            SplitLayout(
                fraction = ui.splitFraction,
                onFraction = { ui.splitFraction = it },
                modifier = canvasModifier,
                first = { panes[0](it) },
                second = { panes[1](it) },
            )
        } else {
            panes[0](canvasModifier)
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

/**
 * Everything [EditorPaneView] needs, bundled so the movable pane content in [EditorBody] can read
 * one [rememberUpdatedState] rather than capturing seven stale parameters at the composition that
 * first built it.
 */
private data class PaneArgs(
    val ui: EditorUiState,
    val settings: AppSettings,
    val onSettingsChange: (AppSettings) -> Unit,
    val tabs: List<TabsUiState>,
    val onActivePane: (Int) -> Unit,
    val onSurfaceCreated: (Int, DrawingSurfaceView) -> Unit,
    val onPickImage: (Placement) -> Unit,
)
