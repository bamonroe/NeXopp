package com.nexopp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nexopp.format.ExportFormat
import com.nexopp.format.SaveFormat
import com.nexopp.render.ImportPdfMode
import com.nexopp.render.captureBackgroundRegion
import com.nexopp.render.clearBackgroundRegion
import com.nexopp.render.clearSelection
import com.nexopp.render.cutBackgroundRegion
import com.nexopp.render.clearTextSelection
import com.nexopp.render.copySelection
import com.nexopp.render.copyTextSelection
import com.nexopp.render.cancelSpline
import com.nexopp.render.cutSelection
import com.nexopp.render.deleteSelection
import com.nexopp.render.duplicateSelection
import com.nexopp.render.finishSpline
import com.nexopp.render.pasteClipboard
import com.nexopp.render.restyleSelection
import com.nexopp.render.undoLastSplineNode

/**
 * Everything layered over the canvas: the contextual action bars along the bottom edge, and the
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
    /**
     * What the open document would lose if it were saved in the given format, one sentence per loss
     * and empty when it would lose nothing. Supplied by the host so the text stays owned by
     * `exportWarnings` and is never composed here.
     */
    saveWarnings: (SaveFormat) -> List<String>,
    onImportPdf: (ImportPdfMode) -> Unit,
    /** Export confirmed: the chosen format, [com.nexopp.format.PageRange] spec, DPI and file stem. */
    onExport: (ExportFormat, String, Int, String) -> Unit,
) {
    val surface = pane.surface
    val palette = rememberColorPaletteState(settings, onSettingsChange)
    val barModifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)

    SelectionOverlays(ui, pane, palette, barModifier, settings, onSettingsChange)

    ui.textPlacement?.let { placement ->
        val existing = placement.existing
        val defaults = ui.textDefaults
        TextBoxDialog(
            title = if (existing != null) "Edit text" else "Add text",
            initialContent = existing?.content ?: "",
            initialFamily = existing?.let { com.nexopp.format.FontDescription.parse(it.font) }?.family ?: defaults.family,
            initialBold = existing?.let { com.nexopp.format.FontDescription.parse(it.font) }?.bold ?: defaults.bold,
            initialItalic = existing?.let { com.nexopp.format.FontDescription.parse(it.font) }?.italic ?: defaults.italic,
            initialSize = existing?.size ?: defaults.size,
            initialColor = existing?.color ?: defaults.color,
            palette = palette,
            onConfirm = { content, family, bold, italic, sizePt, colorArgb ->
                surface?.insertText(
                    placement, content, com.nexopp.format.FontDescription(family, bold, italic).compose(), sizePt, colorArgb
                )
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
            onConfirm = { filename, format ->
                ui.showSaveAs = false
                // A format that would drop something asks first; one that wouldn't says nothing.
                val warnings = saveWarnings(format)
                if (warnings.isEmpty()) {
                    onSaveAs(filename, format)
                } else {
                    ui.pendingLossySave =
                        PendingLossySave(warnings, format) { onSaveAs(filename, format) }
                }
            },
            onDismiss = { ui.showSaveAs = false },
        )
    }
    ui.pendingLossySave?.let { pending ->
        LossySaveDialog(
            warnings = pending.warnings,
            confirmLabel = "Save as ${pending.format.label}",
            onConfirm = { ui.pendingLossySave = null; pending.onConfirm() },
            // Cancel writes nothing and leaves the tab's format exactly as it was.
            onDismiss = { ui.pendingLossySave = null },
        )
    }
    if (ui.lossyDetails.isNotEmpty()) {
        LossySaveDetailsDialog(ui.lossyDetails, onDismiss = { ui.lossyDetails = emptyList() })
    }
    if (ui.showExport) {
        ExportDialog(
            pageCount = pane.pageCount,
            currentPage = pane.currentPage,
            onDismiss = { ui.showExport = false },
            onExport = { format, spec, dpi, baseName ->
                ui.showExport = false
                onExport(format, spec, dpi, baseName)
            },
        )
    }
}

/**
 * The selection and placement bars: the contextual action bars for select tools and text selections.
 * Extracted from [EditorOverlays] to keep the dispatcher small.
 */
@Composable
private fun BoxScope.SelectionOverlays(
    ui: EditorUiState,
    pane: PaneState,
    palette: ColorPaletteState,
    barModifier: Modifier,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val surface = pane.surface
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
    } else if (ui.tool == EditorTool.SELECT || ui.tool == EditorTool.LASSO_SELECT || ui.tool == EditorTool.BG_SELECT) {
        SelectModeBar(
            canPaste = pane.hasClipboard,
            onPaste = {
                // Paste lands a fresh selection, so switch to SELECT first: under BG_SELECT the
                // gesture layer never reaches the selection controller, and the pasted elements
                // would draw as selected yet be undraggable (and die on the next touch).
                if (ui.tool != EditorTool.SELECT && ui.tool != EditorTool.LASSO_SELECT) {
                    ui.tool = EditorTool.SELECT
                    surface?.applyTool(ui.tool)
                    // Point the rail's Select slot at SELECT too, or it would keep facing the
                    // tool we just left and misreport what the canvas is actually in.
                    groupOf(EditorTool.SELECT)?.let {
                        onSettingsChange(
                            settings.copy(
                                toolGroupSelections =
                                    it.withSelection(settings.toolGroupSelections, EditorTool.SELECT),
                            ),
                        )
                    }
                }
                surface?.pasteClipboard()
            },
            modifier = barModifier,
            hasRegion = pane.hasBackgroundRegion,
            onCopyRegion = { surface?.captureBackgroundRegion() },
            onCutRegion = { surface?.cutBackgroundRegion() },
            onClearRegion = { surface?.clearBackgroundRegion() },
        )
    }
    if (pane.splineNodes > 0) {
        SplineModeBar(
            nodeCount = pane.splineNodes,
            onFinish = { surface?.finishSpline() },
            onUndoPoint = { surface?.undoLastSplineNode() },
            onCancel = { surface?.cancelSpline() },
            modifier = barModifier,
        )
    }
    if (pane.hasTextSelection) {
        TextSelectionBar(
            onCopy = { surface?.copyTextSelection() },
            onDeselect = { surface?.clearTextSelection() },
            modifier = barModifier,
        )
    }
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
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(label)
            }
        }
    }
}
