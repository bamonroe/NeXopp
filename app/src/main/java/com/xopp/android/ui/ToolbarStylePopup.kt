package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xopp.android.format.model.LineStyle

/** Line-style labels for the style pop-up, paired with their [LineStyle]. */
private val LINE_STYLE_LABELS: List<Pair<LineStyle, String>> = listOf(
    LineStyle.PLAIN to "Solid",
    LineStyle.DASHED to "Dashed",
    LineStyle.DASH_DOT to "Dash-dot",
    LineStyle.DOTTED to "Dotted",
)

/** Alpha used when fill is switched on and no alpha has been chosen yet (50%, as on desktop). */
const val DEFAULT_FILL_ALPHA: Int = 128

/**
 * The line-style / fill pop-up. Both apply to strokes and shapes drawn next, and round-trip via the
 * `<stroke>` `style`/`fill` attributes. The eraser's mode and size used to live here too; they now
 * belong to the eraser itself — its mode is a long-press choice on the rail's eraser slot, and its
 * size follows the pen's tip sizes (see [com.xopp.android.render.eraserRadiusPt]).
 */
@Composable
internal fun StylePopupButton(
    lineStyle: LineStyle,
    onLineStyle: (LineStyle) -> Unit,
    fill: Int?,
    onFill: (Int?) -> Unit,
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
            FillControls(fill, onFill)
        }
    }
}

/**
 * Shape recognition as a one-tap rail slot: no pop-up, the tap flips it and the slot tints like a
 * tool button while it's on, so a freehand circle can be snapped to a real one (or not) without
 * leaving the page for Settings. Backed by the same persisted `recognizeShapes` setting.
 */
@Composable
internal fun ShapeRecognitionButton(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    val tint =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (enabled) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier,
            )
            .clickable { onEnabled(!enabled) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.ChangeHistory,
            contentDescription = if (enabled) "Shape recognition on" else "Shape recognition off",
            tint = tint,
        )
    }
}

/**
 * Fill as a first-class control: a switch that turns fill on/off plus a continuous alpha slider,
 * mirroring desktop Xournal++. The alpha the user last picked is remembered while the switch is
 * off, so toggling fill back on restores it rather than snapping to a preset.
 */
@Composable
private fun FillControls(fill: Int?, onFill: (Int?) -> Unit) {
    var lastAlpha by remember { mutableStateOf(fill ?: DEFAULT_FILL_ALPHA) }
    val alpha = fill ?: lastAlpha
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (fill == null) "Off" else "${alphaPercent(alpha)}%")
        Switch(
            checked = fill != null,
            onCheckedChange = { on -> onFill(if (on) lastAlpha else null) },
        )
    }
    Slider(
        value = alpha.toFloat(),
        onValueChange = { v ->
            lastAlpha = v.toInt().coerceIn(1, 255)
            onFill(lastAlpha)
        },
        valueRange = 1f..255f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** The alpha 1..255 as a rounded 0..100 percentage, for the fill readout. */
internal fun alphaPercent(alpha: Int): Int = Math.round(alpha * 100f / 255f)
