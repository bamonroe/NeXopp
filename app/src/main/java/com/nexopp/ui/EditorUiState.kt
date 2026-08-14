package com.nexopp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nexopp.format.FontDescription
import com.nexopp.format.SaveFormat
import com.nexopp.format.model.LineStyle
import com.nexopp.render.Placement

/** Default point size for a newly authored text box, and the slider bounds for editing it. */
const val TEXT_SIZE_PT = 12.0
const val TEXT_SIZE_MIN = 6f
const val TEXT_SIZE_MAX = 96f

/**
 * The styling a *new* text box starts from. An edit of an existing box seeds from the element
 * instead; confirming a brand-new box writes its choices back here so the next one matches.
 */
class TextDefaults {
    /** Font family name for new text boxes (e.g. "Sans", "Serif"). */
    var family by mutableStateOf(FontDescription.DEFAULT_FAMILY)
    /** Whether new text boxes are bold. */
    var bold by mutableStateOf(false)
    /** Whether new text boxes are italic. */
    var italic by mutableStateOf(false)
    /** Font size in points for new text boxes. */
    var size by mutableStateOf(TEXT_SIZE_PT)
    /** Font colour (opaque ARGB) for new text boxes. */
    var color by mutableStateOf(PEN_COLORS.first())
}

/**
 * A Save As the user has chosen but not yet confirmed, because the chosen format would lose
 * something. Holding the confirmation as a closure keeps the *decision* here and the *doing* with
 * whoever asked for the save — the UI never learns what a save involves.
 *
 * @property warnings The plain sentences from
 *   [com.nexopp.format.rnote.exportWarnings]; never empty, since an empty list must show nothing.
 * @property format The format that was chosen — the *new* one, which is not sticky yet and so
 *   cannot be read back off the tab.
 * @property onConfirm Run when the user goes ahead anyway. Cancelling simply drops this.
 */
class PendingLossySave(
    val warnings: List<String>,
    val format: SaveFormat,
    val onConfirm: () -> Unit,
)

/**
 * Everything [EditorScreen] remembers that isn't a mirror of a canvas (that's [PaneState]) and isn't
 * persisted (that's [AppSettings]): the live pen, which dialogs are open, and the pending authoring
 * placement.
 *
 * It exists so the screen's regions can be separate composables — each takes this one holder rather
 * than a dozen values and setters — and so the read of a single flag only recomposes the region that
 * reads it.
 */
class EditorUiState(tool: EditorTool, color: Int, width: Float) {
    /** The live pen: the rail's selected tool and the colour/width/style pushed onto the surface. */
    var tool by mutableStateOf(tool)
    /** Current pen colour (opaque ARGB). */
    var color by mutableStateOf(color)
    /** Current pen width in points. */
    var width by mutableStateOf(width)
    /** Current line style (plain/dashed/dash-dot/dotted). */
    var lineStyle by mutableStateOf(LineStyle.PLAIN)

    // Dialog / overlay visibility.
    /** Whether the Settings screen is showing. */
    var showSettings by mutableStateOf(false)
    /** Whether the Save As dialog is showing. */
    var showSaveAs by mutableStateOf(false)
    /** Whether the Import PDF dialog is showing. */
    var showImportPdf by mutableStateOf(false)
    /** Whether the Export dialog is showing. */
    var showExport by mutableStateOf(false)

    /**
     * A Save As whose format would lose something, waiting on a deliberate yes. Non-null puts the
     * warning modal up in place of writing anything.
     */
    var pendingLossySave by mutableStateOf<PendingLossySave?>(null)

    /**
     * The losses of a save that has **already happened**, shown read-only when the user taps
     * *Details* on the snackbar. Empty means the dialog is closed.
     */
    var lossyDetails by mutableStateOf<List<String>>(emptyList())

    /** Full-page (immersive) view: a Hand-tool centre double-tap hides the top bar and side toolbar. */
    var fullPage by mutableStateOf(false)

    /** Where the split bar sits, as the left pane's share of the width. Dragged, not persisted. */
    var splitFraction by mutableStateOf(0.5f)

    // An authoring tap is waiting on its dialog: where the text / LaTeX element goes.
    /** Pending text box placement, or null if none. */
    var textPlacement by mutableStateOf<Placement?>(null)
    /** Pending LaTeX image placement, or null if none. */
    var texPlacement by mutableStateOf<Placement?>(null)

    /** Defaults for new text boxes; updated when a new box is confirmed. */
    val textDefaults = TextDefaults()

    /** One mirror of canvas state per pane; the chrome reads whichever pane has focus. */
    val panes = List(PANE_COUNT) { PaneState() }

    /**
     * Switch to [target], or back to the tool that was live before if [target] is already selected.
     * Used by the barrel double-click bindings, where the same gesture has to toggle both ways.
     */
    fun toggleTool(target: EditorTool) {
        if (tool == target) {
            tool = toolBeforeToggle ?: EditorTool.PEN
            toolBeforeToggle = null
        } else {
            toolBeforeToggle = tool
            tool = target
        }
    }

    private var toolBeforeToggle: EditorTool? = null

    /** The pane the toolbar and overlays drive. */
    fun pane(active: Int): PaneState = panes[active.coerceIn(panes.indices)]
}

/** Build the editor's state holder once, seeded from the persisted [settings]. */
@Composable
fun rememberEditorUiState(settings: AppSettings): EditorUiState = remember {
    EditorUiState(
        tool = startingTool(settings.defaultTool, settings.toolGroupSelections),
        color = settings.lastColor,
        width = settings.lastWidth,
    )
}
