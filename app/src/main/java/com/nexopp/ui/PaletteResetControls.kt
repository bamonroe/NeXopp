package com.nexopp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Which destructive palette edit is awaiting confirmation. */
internal enum class PaletteBulkEdit { RESET, CLEAR }

/**
 * The summary line plus the two bulk actions of the palette editor: restore [RadialPalette.default]
 * or empty every slot. Both discard the current assignments, so both go through a confirm dialog.
 */
@Composable
fun PaletteResetControls(palette: RadialPalette, onChange: (RadialPalette) -> Unit) {
    var pending by remember { mutableStateOf<PaletteBulkEdit?>(null) }
    Text(paletteFilledSummary(palette), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier) {
        OutlinedButton(onClick = { pending = PaletteBulkEdit.RESET }) { Text("Reset to default") }
        OutlinedButton(
            onClick = { pending = PaletteBulkEdit.CLEAR },
            enabled = !palette.isEmpty,
        ) { Text("Clear all slots") }
    }
    pending?.let { edit ->
        ConfirmDialog(
            title = if (edit == PaletteBulkEdit.RESET) "Reset palette?" else "Clear every slot?",
            text = paletteBulkEditPrompt(edit),
            confirmLabel = if (edit == PaletteBulkEdit.RESET) "Reset" else "Clear",
            onConfirm = {
                onChange(if (edit == PaletteBulkEdit.RESET) RadialPalette.default().copy(name = palette.name) else palette.cleared())
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

/** How full the palette is, in prose — e.g. "12 of 24 slots assigned." */
internal fun paletteFilledSummary(palette: RadialPalette): String {
    val total = RadialRing.INNER.slotCount + RadialRing.OUTER.slotCount
    return if (palette.isEmpty) "No slots assigned — the palette would open empty."
    else "${palette.filledCount} of $total slots assigned."
}

/** The body text of the confirm dialog — kept separate so a unit test can assert the wording. */
internal fun paletteBulkEditPrompt(edit: PaletteBulkEdit): String = when (edit) {
    PaletteBulkEdit.RESET ->
        "This replaces every slot with the default tools and pen colours. Your current assignments are lost."
    PaletteBulkEdit.CLEAR ->
        "This empties all 24 slots. The palette will open with nothing in it until you assign slots again."
}
