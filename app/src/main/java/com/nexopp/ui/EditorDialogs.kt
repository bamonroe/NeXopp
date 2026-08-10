package com.nexopp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.nexopp.format.FontDescription
import com.nexopp.format.SaveFormat
import com.nexopp.render.ImportPdfMode
import kotlin.math.roundToInt

/** The families offered in the text dialog — names desktop Xournal++ and Android both resolve. */
private val TEXT_FAMILIES = listOf("Sans", "Serif", "Monospace")

/**
 * "Save As" chooser: name the file and pick the on-disk format. [SaveFormat.ORIGINAL] writes the
 * standard gzip `.xopp` (a PDF background stays linked by location); [SaveFormat.ZIPPED] writes a
 * single self-contained file with the PDF embedded inside (see `docs/architecture.md`). The choice
 * becomes sticky — later plain Saves reuse it — so the picker pre-selects the current format.
 */
@Composable
fun SaveAsDialog(
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
fun ImportPdfDialog(
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
fun FormatOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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

/**
 * The styled text-box editor: content plus the styling the `.xopp` `<text>` element can hold —
 * font family, bold/italic, point size, and colour. Confirms with all five so the caller can
 * compose the font description and place/replace the box. (Underline is intentionally absent —
 * the format can't store it; see the scope rule in `CLAUDE.md`.)
 */
@Composable
fun TextBoxDialog(
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

/** Dropdown to pick a text font family from [TEXT_FAMILIES]. */
@Composable
fun FontFamilyPicker(family: String, onFamily: (String) -> Unit) {
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
