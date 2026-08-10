package com.nexopp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Fixed labels for the three configurable pen-width slots (the widths themselves live in [AppSettings]). */
val PEN_WIDTH_LABELS: List<String> = listOf("S", "M", "L")

/** Minimum allowed pen width in points for the size slider and slot editor. */
const val PEN_WIDTH_MIN: Float = 0.5f

/** Maximum allowed pen width in points for the size slider and slot editor. */
const val PEN_WIDTH_MAX: Float = 15f

/** Increment for the −/+ fine-adjust buttons in the slot-resize dialog, in points. */
const val PEN_WIDTH_STEP: Float = 0.1f

/** Format a pen width for display: at most two decimals, trailing zeros trimmed (e.g. 1.50 → "1.5"). */
fun ptLabel(pt: Float): String =
    String.format(java.util.Locale.US, "%.2f", pt).trimEnd('0').trimEnd('.')



/**
 * The pen-size slot picker: three configurable width slots ([widthSlots]), each shown as a [TipDot]
 * scaled to its width. A tap selects a slot's width; a **long-press** opens a
 * [WidthSlotSliderDialog] that redefines that slot's width (the parent persists it via
 * [onRedefineSlot]). The button face is a dot for the active width, whether or not it matches a slot
 * (it won't right after a re-width of a selection).
 *
 * The same slots size the **eraser** — its radius is derived from the pen width
 * (see [com.nexopp.render.eraserRadiusPt]), so this one popup is the whole tip-size story.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SizePopupButton(
    width: Float,
    widthSlots: List<Float>,
    onWidth: (Float) -> Unit,
    onRedefineSlot: (Int, Float) -> Unit,
) {
    var editing by remember { mutableStateOf(-1) }
    val maxPt = (widthSlots + width).maxOrNull() ?: width
    ToolbarPopupButton(
        face = { open ->
            IconButton(onClick = open) {
                WidthDot(width, maxPt, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { dismiss ->
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
                        onClick = { onWidth(pt); dismiss() },
                        onLongClick = { editing = i; dismiss() },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidthDot(pt, maxPt, MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text("${ptLabel(pt)} pt")
                if (pt == width) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Check, contentDescription = "selected")
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
