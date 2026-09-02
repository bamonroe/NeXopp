package com.nexopp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexopp.render.Momentum
import com.nexopp.render.PanSensitivity
import com.nexopp.render.ShapeWidth

/**
 * Where a row picked up at [from] and dragged [offset] px lands: one place per whole [rowHeight]
 * crossed, rounded so the row commits to a slot once it is more than half way into it. Returns
 * [from] unchanged when nothing is being dragged or the row height isn't measured yet.
 */
fun dragTargetIndex(from: Int, offset: Float, rowHeight: Int, count: Int): Int {
    if (from < 0 || rowHeight <= 0) return from
    return (from + Math.round(offset / rowHeight)).coerceIn(0, count - 1)
}

/** A labelled switch with a title and subtitle. */
@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A titled radio group over an enum's values. */
@Composable
fun <T> OptionGroup(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
    options.forEach { option ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .selectable(selected = option == selected, onClick = { onSelect(option) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = { onSelect(option) })
            Spacer(Modifier.width(8.dp))
            Text(label(option), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        }
    }
}

/** A labelled drop-down menu over an enum's values. */
@Composable
fun <T> DropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(optionLabel(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
    }
}

/**
 * The momentum-strength control: a continuous slider from [Momentum.OFF] to [Momentum.MAX] whose
 * value is snapped to the [Momentum.STEP] grid. Left continuous (no discrete stops) so the wide
 * 0..10 range doesn't render a thicket of tick marks. 0 reads "Off" (a released pan stops dead);
 * every other value shows its factor.
 */
@Composable
fun MomentumSlider(value: Float, onChange: (Float) -> Unit) {
    Text("Momentum scrolling", style = MaterialTheme.typography.bodyLarge)
    Text(
        "How far a one-finger pan keeps gliding after you flick it — the faster you flick, the much " +
            "farther it coasts. 0 turns momentum off; 1 is normal. (Two-finger pans never glide.)",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    val label = if (value <= Momentum.OFF) "Off" else "%.1f×".format(value)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { onChange(Momentum.snap(it)) },
            valueRange = Momentum.OFF..Momentum.MAX,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
    }
}

/**
 * The line-thickness control: a continuous slider over [ShapeWidth.MIN]..[ShapeWidth.MAX], shown as
 * a percentage of the pen's nominal width and snapped to the [ShapeWidth.STEP] grid. Shapes draw at
 * a flat width while a pen stroke is thinned by pressure, so the ratio that makes the two look
 * equal depends on how hard this particular user presses — hence a slider, not a constant.
 */
@Composable
fun ShapeWidthSlider(value: Float, onChange: (Float) -> Unit) {
    Text("Line thickness", style = MaterialTheme.typography.bodyLarge)
    Text(
        "How thick the shape tools (line, arrow, rectangle, ellipse, spline) draw, as a percentage " +
            "of the pen's width. Raise it if your shapes look thin beside your handwriting, lower " +
            "it if they look heavy. Doesn't affect the highlighter or strokes already drawn.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { onChange(ShapeWidth.snap(it)) },
            valueRange = ShapeWidth.MIN..ShapeWidth.MAX,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text("${ShapeWidth.percent(value)}%", modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
    }
}

/**
 * The panning-sensitivity control: a continuous slider from [PanSensitivity.OFF] to
 * [PanSensitivity.MAX], snapped to the [PanSensitivity.STEP] grid. 1 tracks the finger one-to-one
 * (the default); below 1 the canvas pans slower than the finger, above 1 it pans faster. 0 reads
 * "Off" (a pan gesture moves nothing).
 */
@Composable
fun PanSensitivitySlider(value: Float, onChange: (Float) -> Unit) {
    Text("Panning sensitivity", style = MaterialTheme.typography.bodyLarge)
    Text(
        "How far the canvas moves as you pan. 1 matches your finger; lower is slower, higher is faster; 0 turns panning off.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    val label = if (value <= PanSensitivity.OFF) "Off" else "%.1f×".format(value)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { onChange(PanSensitivity.snap(it)) },
            valueRange = PanSensitivity.OFF..PanSensitivity.MAX,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
    }
}

/**
 * One rail position in the Toolbar section: a drag handle, its name and a show/hide switch. The row
 * is grabbed by a **long-press anywhere on it** (not just the handle) and dragged up or down. Every
 * row is placed by [dragOffset]: the held one follows the finger, and the rows it is crossing shift
 * a place to preview where it will land.
 */
@Composable
fun RailItemRow(
    item: RailItem,
    shown: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    onShown: (Boolean) -> Unit,
    onHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val height by rememberUpdatedState(onHeight)
    Surface(
        tonalElevation = if (dragging) 6.dp else 0.dp,
        shadowElevation = if (dragging) 6.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffset }
            .onSizeChanged { height(it.height) }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragStart() },
                    onDrag = { change, amount -> change.consume(); drag(amount.y) },
                    onDragEnd = { dragEnd() },
                    onDragCancel = { dragEnd() },
                )
            },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragIndicator,
                contentDescription = "Drag to reorder ${item.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = shown, onCheckedChange = onShown)
        }
    }
}
