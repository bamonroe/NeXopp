package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xopp.android.format.FontDescription
import com.xopp.android.format.SaveFormat
import com.xopp.android.render.ImportPdfMode
import com.xopp.android.render.clearSelection
import com.xopp.android.render.clearTextSelection
import com.xopp.android.render.copySelection
import com.xopp.android.render.copyTextSelection
import com.xopp.android.render.cutSelection
import com.xopp.android.render.deleteSelection
import com.xopp.android.render.duplicateSelection
import com.xopp.android.render.pasteClipboard
import com.xopp.android.render.restyleSelection
import kotlin.math.roundToInt

/**
 * Everything layered over the canvas: the contextual selection bars along the bottom edge, and the
 * authoring/chooser dialogs. Each one is driven by a single flag on [ui] or [pane], so this is where
 * the screen's "what is open right now" logic lives instead of being strewn through the layout.
 */
@Composable
fun BoxScope.EditorOverlays(
    ui: EditorUiState,
    pane: PaneState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    currentSaveFormat: () -> SaveFormat,
    onSaveAs: (filename: String, format: SaveFormat) -> Unit,
    onImportPdf: (ImportPdfMode) -> Unit,
) {
    val surface = pane.surface
    // One palette for every picker below, so a colour used here shares the pen's recents and
    // custom slot (see ColorPalette.kt).
    val palette = rememberColorPaletteState(settings, onSettingsChange)
    val barModifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)

    // Contextual actions for the Select tools: the full action bar while something is selected,
    // otherwise (in a marquee mode) a small bar offering the clipboard.
    if (pane.hasSelection) {
        SelectionActionBar(
            onCut = { surface?.cutSelection() },
            onCopy = { surface?.copySelection() },
            onDuplicate = { surface?.duplicateSelection() },
            onDelete = { surface?.deleteSelection() },
            onRecolor = { c -> surface?.restyleSelection(c, null) },
            palette = palette,
            onReWidth = { w -> surface?.restyleSelection(null, w.toDouble()) },
            widthSlots = settings.penWidths,
            onDeselect = { surface?.clearSelection() },
            modifier = barModifier,
        )
    } else if (ui.tool == EditorTool.SELECT || ui.tool == EditorTool.LASSO_SELECT) {
        SelectModeBar(
            canPaste = pane.hasClipboard,
            onPaste = { surface?.pasteClipboard() },
            modifier = barModifier,
        )
    }

    // Copy affordance for a PDF-text selection (the text-select tool).
    if (pane.hasTextSelection) {
        TextSelectionBar(
            onCopy = { surface?.copyTextSelection() },
            onDeselect = { surface?.clearTextSelection() },
            modifier = barModifier,
        )
    }

    ui.textPlacement?.let { placement ->
        val existing = placement.existing
        val seedFont = existing?.let { FontDescription.parse(it.font) }
        val defaults = ui.textDefaults
        TextBoxDialog(
            title = if (existing != null) "Edit text" else "Add text",
            initialContent = existing?.content ?: "",
            initialFamily = seedFont?.family ?: defaults.family,
            initialBold = seedFont?.bold ?: defaults.bold,
            initialItalic = seedFont?.italic ?: defaults.italic,
            initialSize = existing?.size ?: defaults.size,
            initialColor = existing?.color ?: defaults.color,
            palette = palette,
            onConfirm = { content, family, bold, italic, sizePt, colorArgb ->
                surface?.insertText(
                    placement, content, FontDescription(family, bold, italic).compose(), sizePt, colorArgb
                )
                // A brand-new box's styling becomes the default for the next one; an edit doesn't.
                if (existing == null) {
                    defaults.family = family; defaults.bold = bold; defaults.italic = italic
                    defaults.size = sizePt; defaults.color = colorArgb
                }
                ui.textPlacement = null
            },
            onDismiss = { surface?.cancelTextEdit(); ui.textPlacement = null },
        )
    }
    ui.texPlacement?.let { placement ->
        TextInputDialog(
            title = "LaTeX",
            initial = "",
            confirmLabel = "Place",
            onConfirm = { latex -> surface?.insertTex(placement, latex, ui.color); ui.texPlacement = null },
            onDismiss = { ui.texPlacement = null },
        )
    }
    if (ui.showImportPdf) {
        ImportPdfDialog(
            merging = surface?.hasPdfBackground() == true,
            onConfirm = { mode -> ui.showImportPdf = false; onImportPdf(mode) },
            onDismiss = { ui.showImportPdf = false },
        )
    }
    if (ui.showSaveAs) {
        SaveAsDialog(
            initialFormat = currentSaveFormat(),
            onConfirm = { filename, format -> ui.showSaveAs = false; onSaveAs(filename, format) },
            onDismiss = { ui.showSaveAs = false },
        )
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

/**
 * "Import PDF" chooser: does the picked PDF become the whole document ([ImportPdfMode.REPLACE], which
 * discards the current pages) or land after the pages already open ([ImportPdfMode.APPEND])? A `.xopp`
 * can reference just one background PDF, so when the document already has one ([merging]) the append
 * merges the two into a single joined PDF — the subtitle says so, since the joined file is what later
 * saves link to (see [ImportPdfMode]).
 */
@Composable
private fun ImportPdfDialog(
    merging: Boolean,
    onConfirm: (ImportPdfMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ImportPdfMode.APPEND) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormatOption(
                    selected = mode == ImportPdfMode.REPLACE,
                    title = "Replace",
                    subtitle = "The PDF's pages become this document, replacing the current pages.",
                    onClick = { mode = ImportPdfMode.REPLACE },
                )
                FormatOption(
                    selected = mode == ImportPdfMode.APPEND,
                    title = "Append",
                    subtitle = if (merging)
                        "Add the PDF's pages after the pages already open, merging it into this document's background PDF."
                    else "Add the PDF's pages after the pages already open.",
                    onClick = { mode = ImportPdfMode.APPEND },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text("Choose PDF…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One selectable option row in [SaveAsDialog]/[ImportPdfDialog]: a radio plus a title and explanation. */
@Composable
private fun FormatOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        // A disabled option stays visible (so the choice is explained) but reads as unavailable.
        val alpha = if (enabled) 1f else 0.38f
        Column(modifier = Modifier.alpha(alpha)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
    palette: ColorPaletteState,
    onConfirm: (content: String, family: String, bold: Boolean, italic: Boolean, sizePt: Double, colorArgb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }
    var family by remember { mutableStateOf(initialFamily) }
    var bold by remember { mutableStateOf(initialBold) }
    var italic by remember { mutableStateOf(initialItalic) }
    var size by remember { mutableStateOf(initialSize.toFloat().coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)) }
    var colorArgb by remember { mutableStateOf(initialColor) }
    var editingColor by remember { mutableStateOf(false) }

    // Outside the AlertDialog below: the HSV editor must outlive the row that opened it.
    CustomColorEditor(visible = editingColor, palette = palette, onDismiss = { editingColor = false })
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
                ColorPaletteRows(
                    selected = colorArgb,
                    palette = palette,
                    onPick = { c -> colorArgb = c; palette.note(c) },
                    onEditCustom = { editingColor = true },
                )
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

/**
 * A modal "please wait" note for a document transfer. Reading or writing a file on a mounted remote
 * share (SSHFS, FTP, cloud) can take seconds, so the wait is shown rather than looking like a hang.
 */
@Composable
fun TransferOverlay(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // Consume every gesture: the canvas must not be edited while its bytes are in flight.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(label)
            }
        }
    }
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

