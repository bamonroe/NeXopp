package com.xopp.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.BarrelDoubleAction
import com.xopp.android.render.Momentum
import com.xopp.android.render.MomentumCurve
import com.xopp.android.render.PaletteInvocation
import com.xopp.android.render.PanSensitivity
import com.xopp.android.render.PressureSensitivity
import com.xopp.android.render.StrokePrecision

/**
 * The body of each settings section page, plus the small controls they share. One composable per
 * [SettingsSection]; the page chrome (bar, back arrow, scrolling) lives in `SettingsScreen.kt`.
 */

/** Stylus behaviours: palm rejection, hover preview, barrel-button action and pressure "feel". */
@Composable
fun StylusSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SwitchRow(
        title = "Finger draws",
        subtitle = "Off: fingers only pan/zoom, so a resting palm can't draw (stylus-first).",
        checked = settings.fingerDraws,
        onCheckedChange = { onChange(settings.copy(fingerDraws = it)) },
    )
    SwitchRow(
        title = "Hover preview",
        subtitle = "Show a ring where a hovering stylus will land.",
        checked = settings.showHover,
        onCheckedChange = { onChange(settings.copy(showHover = it)) },
    )
    SwitchRow(
        title = "Palette haptics",
        subtitle = "Tick as a radial-palette flick crosses slots, and confirm when it commits.",
        checked = settings.paletteHaptics,
        onCheckedChange = { onChange(settings.copy(paletteHaptics = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Barrel button",
        subtitle = "Action while the stylus side-button is held, whatever the tool.",
        options = BarrelAction.values().toList(),
        selected = settings.barrelAction,
        label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        onSelect = { onChange(settings.copy(barrelAction = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Barrel double-click",
        subtitle = "Action for a rapid double-click of the side-button, with the tip off the glass.",
        options = BarrelDoubleAction.values().toList(),
        selected = settings.barrelDoubleAction,
        label = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) },
        onSelect = { onChange(settings.copy(barrelDoubleAction = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Open the palette by touch",
        subtitle = "Touch gesture that summons the radial palette, for a stylus with no side " +
            "button. The side button itself is set above, under Barrel double-click.",
        options = PaletteInvocation.values().toList(),
        selected = settings.paletteInvocation,
        label = { it.label },
        onSelect = { onChange(settings.copy(paletteInvocation = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Pressure sensitivity",
        subtitle = "How firmly you press to thicken the line.",
        options = PressureSensitivity.values().toList(),
        selected = settings.sensitivity,
        label = { it.label },
        onSelect = { onChange(settings.copy(sensitivity = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Stroke precision",
        subtitle = "How much pen detail a stroke keeps. Higher draws rounder curves on a big, " +
            "high-density screen; lower keeps files smaller.",
        options = StrokePrecision.values().toList(),
        selected = settings.strokePrecision,
        label = { it.label },
        onSelect = { onChange(settings.copy(strokePrecision = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SwitchRow(
        title = "Shape recognition",
        subtitle = "Snap a finished freehand stroke to the shape it resembles — line, arrow, " +
            "circle, rectangle, triangle or polyline. Anything unrecognised stays as drawn.",
        checked = settings.recognizeShapes,
        onCheckedChange = { onChange(settings.copy(recognizeShapes = it)) },
    )
}

/** Editor preferences: the tool a document opens in, and how edits snap. */
@Composable
fun EditorSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SwitchRow(
        title = "Snap to grid",
        subtitle = "Shape endpoints land on the page background's ruling.",
        checked = settings.snapToGrid,
        onCheckedChange = { onChange(settings.copy(snapToGrid = it)) },
    )
    SwitchRow(
        title = "Snap rotation",
        subtitle = "Rotating a selection steps in 15° increments.",
        checked = settings.snapRotation,
        onCheckedChange = { onChange(settings.copy(snapRotation = it)) },
    )
    OptionGroup(
        title = "Default tool",
        subtitle = "Which tool is active when a document opens.",
        options = DEFAULT_TOOL_CHOICES,
        selected = settings.defaultTool,
        label = { it.label },
        onSelect = { onChange(settings.copy(defaultTool = it)) },
    )
}

/** Storage budgets: the largest text import allowed, and how much background-PDF cache to keep. */
@Composable
fun StorageSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    OptionGroup(
        title = "Text import limit",
        subtitle = "Opening a text file typesets all of it into a PDF, so a huge log would stall " +
            "the app. Files over this size are refused with a message instead.",
        options = AppSettings.TEXT_IMPORT_LIMIT_CHOICES,
        selected = settings.textImportLimitMb,
        label = { "$it MB" },
        onSelect = { onChange(settings.copy(textImportLimitMb = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "PDF cache limit",
        subtitle = "Generated and imported backgrounds are kept so reopening a file is instant. " +
            "Past this, the oldest unused ones are dropped; they regenerate on the next open.",
        options = AppSettings.PDF_CACHE_LIMIT_CHOICES,
        selected = settings.pdfCacheLimitMb,
        label = { "$it MB" },
        onSelect = { onChange(settings.copy(pdfCacheLimitMb = it)) },
    )
}

/** Toolbar layout: where the rail is docked, and which buttons it shows in what order. */
@Composable
fun ToolbarSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    OptionGroup(
        title = "Toolbar position",
        subtitle = "Which edge the tool rail is docked to.",
        options = ToolbarPosition.values().toList(),
        selected = settings.toolbarPosition,
        label = { it.label },
        onSelect = { onChange(settings.copy(toolbarPosition = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Rail buttons", style = MaterialTheme.typography.titleSmall)
    Text(
        "Switch a button off to hide it, or press and hold a row and drag it up or down to reorder. " +
            "The rail draws them top-to-bottom (left-to-right when docked horizontally).",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    // Drag state. The list is *not* reordered mid-gesture: moving a row would change which item
    // each composable slot holds, restarting the `pointerInput` and cancelling the drag after a
    // single step. So the drag is purely visual — `dragIndex` is where it was picked up and
    // `dragOffset` how far the finger has moved — and the reorder is committed on release.
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0) }

    val items = orderedRailItems(settings.railOrder)
    // The place the held row would land if let go now, and the shift each other row previews to
    // make way for it — so the list still visibly opens a gap under the finger.
    val dropIndex = dragTargetIndex(dragIndex, dragOffset, rowHeightPx, items.size)
    items.forEachIndexed { index, item ->
        RailItemRow(
            item = item,
            shown = item.id !in settings.railHidden,
            dragging = dragIndex == index,
            dragOffset = when {
                dragIndex == index -> dragOffset
                dragIndex < 0 -> 0f
                index in (dragIndex + 1)..dropIndex -> -rowHeightPx.toFloat()
                index in dropIndex until dragIndex -> rowHeightPx.toFloat()
                else -> 0f
            },
            onShown = { shown ->
                val hidden = if (shown) settings.railHidden - item.id else settings.railHidden + item.id
                onChange(settings.copy(railHidden = hidden))
            },
            onHeight = { rowHeightPx = it },
            onDragStart = { dragIndex = index; dragOffset = 0f },
            onDrag = { dy -> dragOffset += dy },
            onDragEnd = {
                val to = dragTargetIndex(dragIndex, dragOffset, rowHeightPx, items.size)
                if (dragIndex >= 0 && to != dragIndex) {
                    onChange(
                        settings.copy(railOrder = moveRailItem(settings.railOrder, dragIndex, to - dragIndex))
                    )
                }
                dragIndex = -1
                dragOffset = 0f
            },
        )
    }
}

/**
 * Where a row picked up at [from] and dragged [offset] px lands: one place per whole [rowHeight]
 * crossed, rounded so the row commits to a slot once it is more than half way into it. Returns
 * [from] unchanged when nothing is being dragged or the row height isn't measured yet.
 */
private fun dragTargetIndex(from: Int, offset: Float, rowHeight: Int, count: Int): Int {
    if (from < 0 || rowHeight <= 0) return from
    return (from + Math.round(offset / rowHeight)).coerceIn(0, count - 1)
}

/**
 * One rail position in the Toolbar section: a drag handle, its name and a show/hide switch. The row
 * is grabbed by a **long-press anywhere on it** (not just the handle) and dragged up or down. Every
 * row is placed by [dragOffset]: the held one follows the finger, and the rows it is crossing shift
 * a place to preview where it will land.
 */
@Composable
private fun RailItemRow(
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
    // `pointerInput` is keyed by the item id alone, so its block — and the drag callbacks captured
    // inside it — is created once and never re-created while the row lives. Reading the callbacks
    // through `rememberUpdatedState` means the gesture always calls the *latest* ones; capturing
    // them directly would commit a reorder against the settings snapshot from first composition,
    // wiping every show/hide toggle made since.
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val height by rememberUpdatedState(onHeight)
    Surface(
        // Lift the held row above its neighbours so it visibly floats over the list it's crossing.
        tonalElevation = if (dragging) 6.dp else 0.dp,
        shadowElevation = if (dragging) 6.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffset }
            // Not keyed by index: re-keying mid-drag would restart the gesture and drop the finger.
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

/** Canvas navigation: momentum scrolling (strength and curve) and panning sensitivity. */
@Composable
fun NavigationSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    MomentumSlider(
        value = settings.momentum,
        onChange = { onChange(settings.copy(momentum = it)) },
    )
    OptionGroup(
        title = "Momentum curve",
        subtitle = "How sharply a faster flick coasts farther: Linear is even, " +
            "Exponential rewards fast swipes the most.",
        options = MomentumCurve.values().toList(),
        selected = settings.momentumCurve,
        label = { it.label },
        onSelect = { onChange(settings.copy(momentumCurve = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    PanSensitivitySlider(
        value = settings.panSensitivity,
        onChange = { onChange(settings.copy(panSensitivity = it)) },
    )
}

/** Appearance: which Material 3 colour scheme the app's chrome is painted with. */
@Composable
fun AppearanceSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    OptionGroup(
        title = "Theme",
        subtitle = "Colours the top bar, tool rail and canvas backdrop. " +
            "System follows the device's light/dark setting.",
        options = ThemeMode.values().toList(),
        selected = settings.themeMode,
        label = { it.label },
        onSelect = { onChange(settings.copy(themeMode = it)) },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

/**
 * The momentum-strength control: a continuous slider from [Momentum.OFF] to [Momentum.MAX] whose
 * value is snapped to the [Momentum.STEP] grid. Left continuous (no discrete stops) so the wide
 * 0..10 range doesn't render a thicket of tick marks. 0 reads "Off" (a released pan stops dead);
 * every other value shows its factor.
 */
@Composable
private fun MomentumSlider(value: Float, onChange: (Float) -> Unit) {
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
 * The panning-sensitivity control: a continuous slider from [PanSensitivity.OFF] to
 * [PanSensitivity.MAX], snapped to the [PanSensitivity.STEP] grid. 1 tracks the finger one-to-one
 * (the default); below 1 the canvas pans slower than the finger, above 1 it pans faster. 0 reads
 * "Off" (a pan gesture moves nothing).
 */
@Composable
private fun PanSensitivitySlider(value: Float, onChange: (Float) -> Unit) {
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

/** A titled radio group over an enum's values. */
@Composable
private fun <T> OptionGroup(
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
