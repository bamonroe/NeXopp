package com.xopp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xopp.android.render.PageStacker
import kotlin.math.roundToInt

/**
 * The page navigator: shows the current page, jumps to the previous/next page, and adds or removes
 * a page. [currentPage] is 0-based; the label and jump targets present it 1-based.
 */
@Composable
internal fun PagesPopupButton(
    pageCount: Int,
    currentPage: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onGoToPage: (Int) -> Unit,
    pageSize: Pair<Double, Double>?,
    onPageSize: (Double, Double) -> Unit,
    pageColumns: Int,
    onPageColumns: (Int) -> Unit,
    pagesEditMode: Boolean,
    onPagesEditMode: (Boolean) -> Unit,
    selectedPages: Int,
    onDeleteSelectedPages: () -> Unit,
    onClearPageSelection: () -> Unit,
    copiedPages: Int,
    onCopySelectedPages: () -> Unit,
    onPastePages: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Description, contentDescription = "Pages")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PageNavRow(pageCount, currentPage, onGoToPage)
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
            PageClipboardItems(
                pageCount = pageCount,
                selectedPages = selectedPages,
                copiedPages = copiedPages,
                onCopySelectedPages = { onCopySelectedPages(); open = false },
                onDeleteSelectedPages = { onDeleteSelectedPages(); open = false },
                onClearPageSelection = { onClearPageSelection(); open = false },
                onPastePages = { onPastePages(); open = false },
            )
            HorizontalDivider()
            PagesPerRowRow(pageColumns, onPageColumns)
            OverviewModeRow(pageColumns, pagesEditMode, onPagesEditMode)
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Page size…") },
                leadingIcon = { Icon(Icons.Filled.AspectRatio, contentDescription = null) },
                trailingIcon = { pageSize?.let { Text(pageSizeLabel(it.first, it.second)) } },
                enabled = pageSize != null,
                onClick = { sizing = true; open = false },
            )
        }
    }
    if (sizing && pageSize != null) {
        PageSizeDialog(
            initialWidthPt = pageSize.first,
            initialHeightPt = pageSize.second,
            onConfirm = { w, h -> onPageSize(w, h); sizing = false },
            onDismiss = { sizing = false },
        )
    }
}

/** The "‹ Page n / N ›" row at the top of the pages menu. */
@Composable
private fun PageNavRow(pageCount: Int, currentPage: Int, onGoToPage: (Int) -> Unit) {
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
}

/**
 * The copy / delete / clear / paste rows. Copy-and-friends only appear once pages have been tapped
 * in the overview grid; paste appears whenever something is on the page clipboard, and lands after
 * the last selected page (or after the page in view when nothing is selected).
 */
@Composable
private fun PageClipboardItems(
    pageCount: Int,
    selectedPages: Int,
    copiedPages: Int,
    onCopySelectedPages: () -> Unit,
    onDeleteSelectedPages: () -> Unit,
    onClearPageSelection: () -> Unit,
    onPastePages: () -> Unit,
) {
    if (selectedPages > 0) {
        DropdownMenuItem(
            text = { Text("Copy $selectedPages selected") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = onCopySelectedPages,
        )
        DropdownMenuItem(
            text = { Text("Delete $selectedPages selected") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            enabled = selectedPages < pageCount,
            onClick = onDeleteSelectedPages,
        )
        DropdownMenuItem(
            text = { Text("Clear selection") },
            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
            onClick = onClearPageSelection,
        )
    }
    if (copiedPages > 0) {
        DropdownMenuItem(
            text = { Text(if (copiedPages == 1) "Paste page" else "Paste $copiedPages pages") },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
            onClick = onPastePages,
        )
    }
}

/** A named page-size preset in points (1/72 in), portrait orientation. */
private data class PagePreset(val name: String, val widthPt: Double, val heightPt: Double)

/** The presets offered in the page-size dialog — desktop Xournal++'s common sizes. */
private val PAGE_PRESETS: List<PagePreset> = listOf(
    PagePreset("A4", 595.276, 841.89),
    PagePreset("A5", 419.528, 595.276),
    PagePreset("Letter", 612.0, 792.0),
    PagePreset("Legal", 612.0, 1008.0),
)

/** Point measurements the dialog can display/enter; [perPt] converts points into that unit. */
private enum class SizeUnit(val label: String, val perPt: Double) {
    MM("mm", 25.4 / 72.0),
    IN("in", 1.0 / 72.0),
    PT("pt", 1.0);

    fun fromPt(pt: Double): Double = pt * perPt
    fun toPt(value: Double): Double = value / perPt
}

/** Format a unit value for a field: at most one decimal, trailing `.0` dropped. */
private fun fmtDim(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/**
 * A short label for the current page size: the matching preset's name (either orientation), else the
 * dimensions in millimetres — used as the trailing hint on the "Page size…" menu row.
 */
private fun pageSizeLabel(widthPt: Double, heightPt: Double): String {
    fun near(a: Double, b: Double) = kotlin.math.abs(a - b) <= 1.0
    PAGE_PRESETS.firstOrNull {
        (near(it.widthPt, widthPt) && near(it.heightPt, heightPt)) ||
            (near(it.widthPt, heightPt) && near(it.heightPt, widthPt))
    }?.let { return it.name }
    return "${fmtDim(widthPt * SizeUnit.MM.perPt)}×${fmtDim(heightPt * SizeUnit.MM.perPt)} mm"
}

/**
 * The page-size chooser: preset buttons (A4/A5/Letter/Legal), a unit toggle (mm/in/pt), width/height
 * fields, and a swap-orientation button. Confirms the chosen size in points to [onConfirm], which the
 * editor applies to the page in view as an undoable edit.
 */
@Composable
private fun PageSizeDialog(
    initialWidthPt: Double,
    initialHeightPt: Double,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var unit by remember { mutableStateOf(SizeUnit.MM) }
    var widthPt by remember { mutableStateOf(initialWidthPt) }
    var heightPt by remember { mutableStateOf(initialHeightPt) }
    var widthText by remember { mutableStateOf(fmtDim(unit.fromPt(initialWidthPt))) }
    var heightText by remember { mutableStateOf(fmtDim(unit.fromPt(initialHeightPt))) }
    // Re-render both fields from the stored point dimensions (after a preset pick, unit change, or swap).
    fun resync() {
        widthText = fmtDim(unit.fromPt(widthPt))
        heightText = fmtDim(unit.fromPt(heightPt))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (preset in PAGE_PRESETS) {
                        TextButton(onClick = {
                            widthPt = preset.widthPt; heightPt = preset.heightPt; resync()
                        }) { Text(preset.name) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Units:", style = MaterialTheme.typography.labelMedium)
                    for (u in SizeUnit.entries) {
                        TextButton(onClick = { if (u != unit) { unit = u; resync() } }) {
                            Text(
                                u.label,
                                fontWeight = if (u == unit) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { entered ->
                            widthText = entered
                            entered.toDoubleOrNull()?.let { widthPt = unit.toPt(it) }
                        },
                        label = { Text("Width") },
                        suffix = { Text(unit.label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                    IconButton(onClick = {
                        val w = widthPt; widthPt = heightPt; heightPt = w; resync()
                    }) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap width and height") }
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { entered ->
                            heightText = entered
                            entered.toDoubleOrNull()?.let { heightPt = unit.toPt(it) }
                        },
                        label = { Text("Height") },
                        suffix = { Text(unit.label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(widthPt, heightPt) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The page-overview control: how many pages sit side by side. One is the plain single-page stack;
 * two or more zooms out to a grid of page thumbnails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagesPerRowRow(pageColumns: Int, onPageColumns: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Pages per row", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageStacker.COLUMN_CHOICES.forEach { n ->
                FilterChip(
                    selected = n == pageColumns,
                    onClick = { onPageColumns(n) },
                    label = { Text("$n") },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

/**
 * The page-overview mode control: **View** keeps the grid a pure display/navigation surface (a tap
 * jumps to the page you hit), **Edit** turns on selection, drag-to-reorder and the copy/delete
 * tooling. Only meaningful in the grid, so it's disabled at a single column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewModeRow(pageColumns: Int, editMode: Boolean, onEditMode: (Boolean) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Overview mode", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !editMode,
                enabled = pageColumns > 1,
                onClick = { onEditMode(false) },
                label = { Text("View") },
                modifier = Modifier.padding(end = 4.dp),
            )
            FilterChip(
                selected = editMode,
                enabled = pageColumns > 1,
                onClick = { onEditMode(true) },
                label = { Text("Edit") },
            )
        }
    }
}
