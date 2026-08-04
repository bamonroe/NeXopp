package com.xopp.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xopp.android.format.FontDescription
import com.xopp.android.format.model.LineStyle
import com.xopp.android.render.Placement

/** Default point size for a newly authored text box, and the slider bounds for editing it. */
const val TEXT_SIZE_PT = 12.0
const val TEXT_SIZE_MIN = 6f
const val TEXT_SIZE_MAX = 96f

/**
 * The styling a *new* text box starts from. An edit of an existing box seeds from the element
 * instead; confirming a brand-new box writes its choices back here so the next one matches.
 */
class TextDefaults {
    var family by mutableStateOf(FontDescription.DEFAULT_FAMILY)
    var bold by mutableStateOf(false)
    var italic by mutableStateOf(false)
    var size by mutableStateOf(TEXT_SIZE_PT)
    var color by mutableStateOf(PEN_COLORS.first())
}

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
    var color by mutableStateOf(color)
    var width by mutableStateOf(width)
    var lineStyle by mutableStateOf(LineStyle.PLAIN)

    // Dialog / overlay visibility.
    var showSettings by mutableStateOf(false)
    var showSaveAs by mutableStateOf(false)
    var showImportPdf by mutableStateOf(false)

    /** Full-page (immersive) view: a Hand-tool centre double-tap hides the top bar and side toolbar. */
    var fullPage by mutableStateOf(false)

    /** Where the split bar sits, as the left pane's share of the width. Dragged, not persisted. */
    var splitFraction by mutableStateOf(0.5f)

    // An authoring tap is waiting on its dialog: where the text / LaTeX element goes.
    var textPlacement by mutableStateOf<Placement?>(null)
    var texPlacement by mutableStateOf<Placement?>(null)

    val textDefaults = TextDefaults()

    /** One mirror of canvas state per pane; the chrome reads whichever pane has focus. */
    val panes = List(PANE_COUNT) { PaneState() }

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
