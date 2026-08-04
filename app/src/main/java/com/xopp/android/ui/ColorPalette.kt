package com.xopp.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The colour state every picker in the app shares — the editable custom slot and the recently-used
 * list, both persisted in [AppSettings]. Holding it in one object is what makes a colour picked for
 * a text box, a selection, or the pen land in the *same* recents and read the *same* custom slot.
 */
@Stable
class ColorPaletteState(
    private val settings: AppSettings,
    private val onSettingsChange: (AppSettings) -> Unit,
) {
    /** The user-defined colour behind the palette's editable slot. */
    val custom: Int get() = settings.customColor

    /** Colours used recently, most-recent-first. */
    val recents: List<Int> get() = settings.recentColors

    /**
     * Record [color] as just used, so it heads the recents row wherever it was picked. [asPen] also
     * makes it the pen colour restored on the next launch — true only when the pen itself picked it.
     */
    fun note(color: Int, asPen: Boolean = false) = onSettingsChange(settings.withColorUsed(color, asPen))

    /** Persist a new colour for the editable custom slot. */
    fun redefineCustom(color: Int) = onSettingsChange(settings.copy(customColor = color))
}

@Composable
fun rememberColorPaletteState(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
): ColorPaletteState = remember(settings, onSettingsChange) {
    ColorPaletteState(settings, onSettingsChange)
}

/**
 * The one colour picker: the fixed [PEN_COLORS] row followed by the editable **custom** slot (marked
 * with a pencil, long-press to redefine via [CustomColorPickerDialog]), then the recents row. Used by
 * the toolbar's pen palette, the text-box dialog and the selection recolour menu, so all three offer
 * the same affordances and share one custom slot and one recents list.
 *
 * A tap reports the colour through [onPick]; the host decides what it means (set the pen, restyle
 * the selection, colour the text) and records the use via [ColorPaletteState.note]. A long-press on
 * the custom slot reports [onEditCustom] — the host closes whatever menu it is in and shows the
 * [CustomColorEditor], which must sit *outside* that menu so dismissing the menu doesn't take the
 * dialog with it.
 */
@Composable
fun ColorPaletteRows(
    selected: Int?,
    palette: ColorPaletteState,
    onPick: (Int) -> Unit,
    onEditCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PaletteHint("Tap to pick · long-press ✎ to edit")
        SwatchRow {
            for (c in PEN_COLORS) {
                ColorSwatch(color = c, selected = c == selected, onClick = { onPick(c) })
            }
            ColorSwatch(
                color = palette.custom,
                selected = palette.custom == selected,
                onClick = { onPick(palette.custom) },
                onLongClick = onEditCustom,
                editable = true,
            )
        }
        if (palette.recents.isNotEmpty()) {
            PaletteHint("Recent")
            SwatchRow {
                for (c in palette.recents) {
                    ColorSwatch(color = c, selected = c == selected, onClick = { onPick(c) })
                }
            }
        }
    }
}

/**
 * The HSV dialog behind the palette's custom slot, shown while [visible]. Confirming reports the new
 * colour through [onRedefine] — by default persisting it as *the* custom slot for every picker.
 */
@Composable
fun CustomColorEditor(
    visible: Boolean,
    palette: ColorPaletteState,
    onDismiss: () -> Unit,
    onRedefine: (Int) -> Unit = { palette.redefineCustom(it) },
) {
    if (!visible) return
    CustomColorPickerDialog(
        initial = palette.custom,
        onConfirm = { newColor -> onRedefine(newColor); onDismiss() },
        onDismiss = onDismiss,
    )
}

/**
 * The swatches, wrapping onto further lines when the host is narrow. A dialog is much tighter than
 * the toolbar's drop-down, and a single fixed row silently clipped the custom slot off its end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun PaletteHint(text: String) {
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
fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    editable: Boolean = false,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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
