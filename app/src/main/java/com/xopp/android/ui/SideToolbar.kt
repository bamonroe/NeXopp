package com.xopp.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Tool
import com.xopp.android.render.EraserMode
import com.xopp.android.render.LayerInfo
import kotlin.math.roundToInt

/** Fixed labels for the three configurable pen-width slots (the widths themselves live in [AppSettings]). */
val PEN_WIDTH_LABELS: List<String> = listOf("S", "M", "L")

/** Bounds of the long-press slot-resize slider, in points. */
const val PEN_WIDTH_MIN: Float = 0.5f
const val PEN_WIDTH_MAX: Float = 15f

/** Increment for the −/+ fine-adjust buttons in the slot-resize dialog, in points. */
const val PEN_WIDTH_STEP: Float = 0.1f

/** Format a pen width for display: at most two decimals, trailing zeros trimmed (e.g. 1.50 → "1.5"). */
fun ptLabel(pt: Float): String =
    String.format(java.util.Locale.US, "%.2f", pt).trimEnd('0').trimEnd('.')

/** The pen palette offered in the chrome. Colours are opaque ARGB; highlighter renders them translucent. */
val PEN_COLORS: List<Int> = listOf(
    0xFF000000.toInt(), // black
    0xFFE00000.toInt(), // red
    0xFF2060E0.toInt(), // blue
    0xFF1E9E1E.toInt(), // green
    0xFFF08000.toInt(), // orange
    0xFFF0D000.toInt(), // yellow
)

/**
 * The editor's interaction modes. PEN/HIGHLIGHTER/ERASER map to the document [Tool]; HAND is a
 * view-only pan mode; SELECT rubber-band-selects objects to move/delete them; TEXT/IMAGE/TEXIMAGE
 * are authoring modes where a canvas tap places (or edits) that element
 * (see [com.xopp.android.render.PlaceKind]).
 */
enum class EditorTool {
    PEN, HIGHLIGHTER, ERASER, HAND, SELECT, TEXT_SELECT, TEXT, IMAGE, TEXIMAGE,
    LINE, ARROW, RECTANGLE, ELLIPSE,
}

/** The four geometric shape tools — drawn as ordinary pen strokes (see [ShapeKind]). */
val SHAPE_TOOLS: List<EditorTool> =
    listOf(EditorTool.LINE, EditorTool.ARROW, EditorTool.RECTANGLE, EditorTool.ELLIPSE)

private data class ToolInfo(val tool: EditorTool, val label: String, val icon: ImageVector)

private val TOOLS: List<ToolInfo> = listOf(
    ToolInfo(EditorTool.PEN, "Pen", Icons.Filled.Create),
    ToolInfo(EditorTool.HIGHLIGHTER, "Highlighter", Icons.Filled.Brush),
    ToolInfo(EditorTool.ERASER, "Eraser", Icons.Filled.Delete),
    ToolInfo(EditorTool.LINE, "Line", Icons.Filled.HorizontalRule),
    ToolInfo(EditorTool.ARROW, "Arrow", Icons.Filled.ArrowRightAlt),
    ToolInfo(EditorTool.RECTANGLE, "Rectangle", Icons.Filled.Rectangle),
    ToolInfo(EditorTool.ELLIPSE, "Ellipse", Icons.Filled.RadioButtonUnchecked),
    ToolInfo(EditorTool.HAND, "Hand (pan)", Icons.Filled.PanTool),
    ToolInfo(EditorTool.SELECT, "Select", Icons.Filled.HighlightAlt),
    ToolInfo(EditorTool.TEXT_SELECT, "Select text (PDF)", Icons.Filled.SelectAll),
    ToolInfo(EditorTool.TEXT, "Text", Icons.Filled.TextFields),
    ToolInfo(EditorTool.IMAGE, "Image", Icons.Filled.Image),
    ToolInfo(EditorTool.TEXIMAGE, "LaTeX", Icons.Filled.Functions),
)

/** Human-readable label for a tool (the same text the rail's tool menu shows). */
val EditorTool.label: String get() = TOOLS.first { it.tool == this }.label

/**
 * The tools that make sense to *start* a document in, offered by the "Default tool" setting. The
 * place-modes (TEXT/IMAGE/TEXIMAGE) and SELECT aren't here: opening straight into them would strand
 * the user in a mode with nothing to act on, so a default is one of the four drawing/pan tools.
 */
val DEFAULT_TOOL_CHOICES: List<EditorTool> =
    listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.ERASER, EditorTool.HAND)

/**
 * The vertical control rail down the left edge: Tool, Colour, Size, Zoom, and a page navigator —
 * each a button opening a small [DropdownMenu] anchored to its own button (which opens to the right
 * of the rail). [EditorScreen] pushes the picked value onto the
 * [com.xopp.android.render.DrawingSurfaceView].
 */
@Composable
fun SideToolbar(
    horizontal: Boolean = false,
    tool: EditorTool,
    onTool: (EditorTool) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
    customColor: Int,
    onRedefineCustom: (Int) -> Unit,
    width: Float,
    onWidth: (Float) -> Unit,
    widthSlots: List<Float>,
    onRedefineSlot: (Int, Float) -> Unit,
    lineStyle: LineStyle,
    onLineStyle: (LineStyle) -> Unit,
    fill: Int?,
    onFill: (Int?) -> Unit,
    eraserMode: EraserMode,
    onEraserMode: (EraserMode) -> Unit,
    layers: List<LayerInfo>,
    hasSelection: Boolean,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Int) -> Unit,
    onRenameLayer: (Int, String) -> Unit,
    onMoveLayer: (Int, Int) -> Unit,
    onActivateLayer: (Int) -> Unit,
    onToggleLayerHidden: (Int, Boolean) -> Unit,
    onMoveSelectionToLayer: (Int) -> Unit,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    pageCount: Int,
    currentPage: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onGoToPage: (Int) -> Unit,
    backgroundStyle: String?,
    onBackgroundStyle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons: @Composable () -> Unit = {
        ToolPopupButton(tool, onTool)
        ColorPopupButton(color, onColor, customColor, onRedefineCustom)
        SizePopupButton(width, widthSlots, onWidth, onRedefineSlot)
        StylePopupButton(lineStyle, onLineStyle, fill, onFill, eraserMode, onEraserMode)
        LayersPopupButton(
            layers, hasSelection, onAddLayer, onDeleteLayer, onRenameLayer,
            onMoveLayer, onActivateLayer, onToggleLayerHidden, onMoveSelectionToLayer,
        )
        ZoomPopupButton(zoom, onZoomIn, onZoomOut, onZoomReset)
        BackgroundPopupButton(backgroundStyle, onBackgroundStyle)
        PagesPopupButton(pageCount, currentPage, onAddPage, onRemovePage, onGoToPage)
    }
    if (horizontal) {
        Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    } else {
        Surface(modifier = modifier.fillMaxHeight(), tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { buttons() }
        }
    }
}

@Composable
private fun ToolPopupButton(tool: EditorTool, onTool: (EditorTool) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = TOOLS.first { it.tool == tool }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(current.icon, contentDescription = "Tool: ${current.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (info in TOOLS) {
                DropdownMenuItem(
                    text = { Text(info.label) },
                    leadingIcon = { Icon(info.icon, contentDescription = null) },
                    trailingIcon = {
                        if (info.tool == tool) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onTool(info.tool); open = false },
                )
            }
        }
    }
}

/**
 * The colour picker: the fixed [PEN_COLORS] palette followed by one editable **custom** slot (marked
 * with a pencil). Tapping any swatch selects it; **long-pressing the custom slot** opens a
 * [CustomColorPickerDialog] whose result the parent persists via [onRedefineCustom].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorPopupButton(
    color: Int,
    onColor: (Int) -> Unit,
    customColor: Int,
    onRedefineCustom: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Circle, contentDescription = "Colour", tint = Color(color))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Tap to pick · long-press ✎ to edit",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (c in PEN_COLORS) {
                    Swatch(color = c, selected = c == color, onClick = { onColor(c); open = false })
                }
                Swatch(
                    color = customColor,
                    selected = customColor == color,
                    onClick = { onColor(customColor); open = false },
                    onLongClick = { editing = true; open = false },
                    editable = true,
                )
            }
        }
    }
    if (editing) {
        CustomColorPickerDialog(
            initial = customColor,
            onConfirm = { newColor -> onRedefineCustom(newColor); editing = false },
            onDismiss = { editing = false },
        )
    }
}

/**
 * The pen-size slot picker: three configurable width slots ([widthSlots], labelled S/M/L). A tap
 * selects a slot's width; a **long-press** opens a [WidthSlotSliderDialog] that redefines that slot's
 * width (the parent persists it via [onRedefineSlot]). The button face shows the active slot's label,
 * or "?" when the active width matches no slot (e.g. right after a re-width).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SizePopupButton(
    width: Float,
    widthSlots: List<Float>,
    onWidth: (Float) -> Unit,
    onRedefineSlot: (Int, Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(-1) }
    val currentLabel = widthSlots.indexOfFirst { it == width }
        .let { if (it >= 0) PEN_WIDTH_LABELS[it] else "?" }
    Box {
        TextButton(onClick = { open = true }) {
            Text(currentLabel)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Tap to pick · long-press to resize",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            widthSlots.forEachIndexed { i, pt ->
                Row(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onWidth(pt); open = false },
                            onLongClick = { editing = i; open = false },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${PEN_WIDTH_LABELS[i]}  (${ptLabel(pt)} pt)")
                    if (pt == width) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.Check, contentDescription = "selected")
                    }
                }
            }
        }
    }
    if (editing in widthSlots.indices) {
        WidthSlotSliderDialog(
            label = PEN_WIDTH_LABELS[editing],
            initial = widthSlots[editing],
            onConfirm = { newPt -> onRedefineSlot(editing, newPt); editing = -1 },
            onDismiss = { editing = -1 },
        )
    }
}

/**
 * A dialog (0.5 → 15 pt) that redefines one pen-width slot; opened by long-pressing the slot. Offers
 * three ways to dial in a width: the slider for a broad sweep, the −/+ buttons for fine
 * [PEN_WIDTH_STEP]-pt nudges, and a text field for typing an exact value.
 */
@Composable
private fun WidthSlotSliderDialog(
    label: String,
    initial: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX)) }
    // The text field is edited freely as a string so mid-edit values ("", ".", "1.") don't fight the
    // user; a valid, in-range parse flows back into [value], and any programmatic change re-syncs it.
    var text by remember { mutableStateOf(ptLabel(value)) }
    fun setValue(pt: Float) {
        value = pt.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX)
        text = ptLabel(value)
    }
    // Snap −/+ nudges to a clean [PEN_WIDTH_STEP] grid so repeated taps don't accumulate float drift.
    fun nudge(delta: Float) = setValue(((value + delta) / PEN_WIDTH_STEP).roundToInt() * PEN_WIDTH_STEP)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Slot $label width") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { nudge(-PEN_WIDTH_STEP) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Thinner")
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { entered ->
                            text = entered
                            entered.toFloatOrNull()?.let { value = it.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX) }
                        },
                        singleLine = true,
                        suffix = { Text("pt") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                    IconButton(onClick = { nudge(PEN_WIDTH_STEP) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Thicker")
                    }
                }
                Slider(
                    value = value,
                    onValueChange = { setValue(it) },
                    valueRange = PEN_WIDTH_MIN..PEN_WIDTH_MAX,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoomPopupButton(zoom: Float, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onZoomReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${(zoom * 100).roundToInt()}%")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onZoomOut) { Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out") }
                TextButton(onClick = onZoomReset) { Text("${(zoom * 100).roundToInt()}%") }
                IconButton(onClick = onZoomIn) { Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in") }
            }
        }
    }
}

/**
 * Paper-style labels for the background pop-up, paired with the `<background style=…>` value written
 * to the `.xopp` file. Names match desktop Xournal++ verbatim so files round-trip; the renderer draws
 * each in [com.xopp.android.render.BackgroundRenderer].
 */
private val BACKGROUND_STYLES: List<Pair<String, String>> = listOf(
    "plain" to "Plain",
    "lined" to "Lined",
    "ruled" to "Ruled",
    "graph" to "Graph",
    "dotted" to "Dotted",
)

/**
 * The page-background chooser: sets the current page's paper style (plain/lined/ruled/graph/dotted).
 * [style] is the current page's style, or null when the page is a PDF/pixmap (no solid sheet to
 * restyle) — in which case the items are disabled.
 */
@Composable
private fun BackgroundPopupButton(style: String?, onBackgroundStyle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.GridOn, contentDescription = "Page background")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in BACKGROUND_STYLES) {
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = style != null,
                    trailingIcon = {
                        if (value == style) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onBackgroundStyle(value); open = false },
                )
            }
        }
    }
}

/**
 * The page navigator: shows the current page, jumps to the previous/next page, and adds or removes
 * a page. [currentPage] is 0-based; the label and jump targets present it 1-based.
 */
@Composable
private fun PagesPopupButton(
    pageCount: Int,
    currentPage: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Description, contentDescription = "Pages")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onGoToPage(currentPage - 1) },
                    enabled = currentPage > 0,
                ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page") }
                Text(
                    "Page ${(currentPage + 1).coerceAtMost(pageCount)} / $pageCount",
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = { onGoToPage(currentPage + 1) },
                    enabled = currentPage < pageCount - 1,
                ) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next page") }
            }
            DropdownMenuItem(
                text = { Text("Add page") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddPage() },
            )
            DropdownMenuItem(
                text = { Text("Remove page") },
                leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null) },
                enabled = pageCount > 1,
                onClick = { onRemovePage() },
            )
        }
    }
}

/** Line-style labels for the style pop-up, paired with their [LineStyle]. */
private val LINE_STYLE_LABELS: List<Pair<LineStyle, String>> = listOf(
    LineStyle.PLAIN to "Solid",
    LineStyle.DASHED to "Dashed",
    LineStyle.DASH_DOT to "Dash-dot",
    LineStyle.DOTTED to "Dotted",
)

/** Fill presets for the style pop-up: a label and the alpha (null = no fill). */
private val FILL_LEVELS: List<Pair<String, Int?>> = listOf(
    "None" to null,
    "Light (25%)" to 64,
    "Medium (50%)" to 128,
    "Heavy (75%)" to 192,
    "Solid (100%)" to 255,
)

/**
 * The line-style / fill / eraser-mode pop-up. Line style and fill apply to strokes and shapes drawn
 * next (they round-trip via the `<stroke>` `style`/`fill` attributes); the eraser mode chooses
 * between rubbing out touched segments and deleting whole strokes.
 */
@Composable
private fun StylePopupButton(
    lineStyle: LineStyle,
    onLineStyle: (LineStyle) -> Unit,
    fill: Int?,
    onFill: (Int?) -> Unit,
    eraserMode: EraserMode,
    onEraserMode: (EraserMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Timeline, contentDescription = "Line style & fill")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Line style")
            for ((style, label) in LINE_STYLE_LABELS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (style == lineStyle) Icon(Icons.Filled.Check, contentDescription = "selected") },
                    onClick = { onLineStyle(style) },
                )
            }
            MenuHeading("Fill")
            for ((label, alpha) in FILL_LEVELS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (alpha == fill) Icon(Icons.Filled.Check, contentDescription = "selected") },
                    onClick = { onFill(alpha) },
                )
            }
            MenuHeading("Eraser")
            DropdownMenuItem(
                text = { Text("Standard (partial)") },
                trailingIcon = { if (eraserMode == EraserMode.STANDARD) Icon(Icons.Filled.Check, contentDescription = "selected") },
                onClick = { onEraserMode(EraserMode.STANDARD) },
            )
            DropdownMenuItem(
                text = { Text("Delete whole stroke") },
                trailingIcon = { if (eraserMode == EraserMode.WHOLE_STROKE) Icon(Icons.Filled.Check, contentDescription = "selected") },
                onClick = { onEraserMode(EraserMode.WHOLE_STROKE) },
            )
        }
    }
}

/**
 * The layer manager: a top-down list of the visible page's layers, each row toggling visibility,
 * selecting the active layer (where new ink lands), reordering (up/down z-order), renaming, and
 * deleting; plus "Add layer" and — when something is selected — "Move selection here".
 */
@Composable
private fun LayersPopupButton(
    layers: List<LayerInfo>,
    hasSelection: Boolean,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Int) -> Unit,
    onRenameLayer: (Int, String) -> Unit,
    onMoveLayer: (Int, Int) -> Unit,
    onActivateLayer: (Int) -> Unit,
    onToggleLayerHidden: (Int, Boolean) -> Unit,
    onMoveSelectionToLayer: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(-1) }
    var renameLabel by remember { mutableStateOf("") }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Layers, contentDescription = "Layers")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Layers (top first)")
            // Show top layer first (model is bottom-up), so the list matches z-order on screen.
            for (info in layers.asReversed()) {
                LayerRow(
                    info = info,
                    canMoveUp = info.index < layers.lastIndex,
                    canMoveDown = info.index > 0,
                    canDelete = layers.size > 1,
                    hasSelection = hasSelection,
                    onActivate = { onActivateLayer(info.index) },
                    onToggleHidden = { onToggleLayerHidden(info.index, info.visible) },
                    onMoveUp = { onMoveLayer(info.index, info.index + 1) },
                    onMoveDown = { onMoveLayer(info.index, info.index - 1) },
                    onRename = { renaming = info.index; renameLabel = info.label },
                    onDelete = { onDeleteLayer(info.index) },
                    onMoveSelectionHere = { onMoveSelectionToLayer(info.index) },
                )
            }
            DropdownMenuItem(
                text = { Text("Add layer") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddLayer() },
            )
        }
    }
    if (renaming >= 0) {
        LayerRenameDialog(
            initial = renameLabel,
            onConfirm = { name -> onRenameLayer(renaming, name); renaming = -1 },
            onDismiss = { renaming = -1 },
        )
    }
}

/** One layer row: an active-dot + name (tap to activate), then visibility / up / down / rename / delete. */
@Composable
private fun LayerRow(
    info: LayerInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    hasSelection: Boolean,
    onActivate: () -> Unit,
    onToggleHidden: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
        }
        if (hasSelection) {
            IconButton(onClick = onMoveSelectionHere, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.HighlightAlt, contentDescription = "Move selection here")
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename layer")
        }
        IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete layer")
        }
    }
}

/** A dialog to rename a layer (blank clears the custom name). */
@Composable
private fun LayerRenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename layer") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small non-clickable section heading inside a dropdown menu. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A colour swatch: a filled circle with a selection ring. Passing [onLongClick] makes it respond to a
 * long-press (used by the editable custom slot); [editable] overlays a small pencil to mark that slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    editable: Boolean = false,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000)
    val ringWidth = if (selected) 3.dp else 1.dp
    val clickModifier = if (onLongClick != null)
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    else Modifier.clickable(onClick = onClick)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(ringWidth, ring, CircleShape)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (editable) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Edit custom colour",
                tint = if (Color(color).luminance() < 0.5f) Color.White else Color.Black,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
