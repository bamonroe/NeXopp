package com.nexopp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.nexopp.format.model.LineStyle
import com.nexopp.render.GuideKind

private val LINE_STYLE_LABELS: List<Pair<LineStyle, String>> = listOf(
    LineStyle.PLAIN to "Solid",
    LineStyle.DASHED to "Dashed",
    LineStyle.DASH_DOT to "Dash-dot",
    LineStyle.DOTTED to "Dotted",
)

/** Mini fill controls for the inline line tool menu. */
@Composable
private fun FillControlsMini(fill: Int?, onFill: (Int?) -> Unit, onDismiss: () -> Unit) {
    var lastAlpha by remember { mutableStateOf(fill ?: DEFAULT_FILL_ALPHA) }
    val alpha = fill ?: lastAlpha
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (fill == null) "Off" else "${alphaPercent(alpha)}%")
        androidx.compose.material3.Switch(
            checked = fill != null,
            onCheckedChange = { on ->
                onFill(if (on) lastAlpha else null)
                onDismiss()
            },
        )
    }
    androidx.compose.material3.Slider(
        value = alpha.toFloat(),
        onValueChange = { v ->
            lastAlpha = v.toInt().coerceIn(1, 255)
            onFill(lastAlpha)
        },
        valueRange = 1f..255f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/**
 * The vertical control rail down the left edge: Tool, Colour, Size, Zoom, and a page navigator —
 * each a button opening a small [DropdownMenu] anchored to its own button (which opens to the right
 * of the rail). [EditorScreen] pushes the picked value onto the
 * [com.nexopp.render.DrawingSurfaceView].
 *
 * The rail is only the shell and the dispatch: each slot's pop-up lives in its own
 * `Toolbar*Popup.kt` sibling file.
 */
@Composable
fun SideToolbar(
    horizontal: Boolean = false,
    tool: EditorTool,
    onTool: (EditorTool) -> Unit,
    toolGroupSelections: Map<String, EditorTool>,
    onToolGroupSelections: (Map<String, EditorTool>) -> Unit,
    styleCallbacks: ToolbarStyleCallbacks,
    presets: List<ToolPreset> = emptyList(),
    onPresets: (List<ToolPreset>) -> Unit = {},
    onActivatePreset: (ToolPreset) -> Unit = {},
    onCapturePreset: (String) -> ToolPreset = { ToolPreset(it, it, tool, styleCallbacks.color, styleCallbacks.width) },
    recognizeShapes: Boolean = false,
    onRecognizeShapes: (Boolean) -> Unit = {},
    guideKind: GuideKind,
    onGuideKind: (GuideKind) -> Unit,
    layerCallbacks: ToolbarLayerCallbacks,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    pageCallbacks: ToolbarPagesCallbacks,
    backgroundStyle: String?,
    onBackgroundStyle: (String) -> Unit,
    audio: AudioUiState = AudioUiState(),
    railOrder: List<String> = emptyList(),
    railHidden: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    ToolbarShell(horizontal = horizontal, modifier = modifier) {
        for (item in visibleRailItems(railOrder, railHidden)) {
            val group = toolGroupForRailItem(item.id)
            if (group != null) {
                // Line group gets inline style/width controls
                val passStyle = group.id == "line"
                val passWidth = group.id == "line"
                ToolGroupButton(
                    group = group,
                    selected = group.selected(toolGroupSelections),
                    active = tool in group.tools,
                    onTool = onTool,
                    onSelect = { picked ->
                        onToolGroupSelections(group.withSelection(toolGroupSelections, picked))
                        onTool(picked)
                    },
                    lineStyle = if (passStyle) styleCallbacks.lineStyle else null,
                    onLineStyle = if (passStyle) styleCallbacks.onLineStyle else null,
                    fill = if (passStyle) styleCallbacks.fill else null,
                    onFill = if (passStyle) styleCallbacks.onFill else null,
                    width = if (passWidth) styleCallbacks.width else null,
                    widthSlots = if (passWidth) styleCallbacks.widthSlots else null,
                    onWidth = if (passWidth) styleCallbacks.onWidth else null,
                    onRedefineSlot = if (passWidth) styleCallbacks.onRedefineSlot else null,
                )
            } else when (item.id) {
                "color" -> ColorPopupButton(styleCallbacks.color, styleCallbacks.onColor, styleCallbacks.palette, styleCallbacks.onRedefineCustom)
                "size" -> SizePopupButton(styleCallbacks.width, styleCallbacks.widthSlots, styleCallbacks.onWidth, styleCallbacks.onRedefineSlot)
                "style" -> StylePopupButton(styleCallbacks.lineStyle, styleCallbacks.onLineStyle, styleCallbacks.fill, styleCallbacks.onFill)
                "presets" -> PresetsPopupButton(presets, onPresets, onActivatePreset, onCapturePreset)
                "shapes" -> ShapeRecognitionButton(recognizeShapes, onRecognizeShapes)
                "guides" -> GuidePopupButton(guideKind, onGuideKind)
                "layers" -> LayersPopupButton(layerCallbacks)
                "zoom" -> ZoomPopupButton(zoom, onZoomIn, onZoomOut, onZoomReset)
                "background" -> BackgroundPopupButton(backgroundStyle, onBackgroundStyle)
                "pages" -> PagesPopupButton(pageCallbacks)
                "audio" -> AudioPopupButton(audio)
            }
        }
    }
}

/** The rail's surface: a scrolling column down the edge, or a scrolling row across the top. */
@Composable
private fun ToolbarShell(horizontal: Boolean, modifier: Modifier, buttons: @Composable () -> Unit) {
    if (horizontal) {
        Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    } else {
        Surface(modifier = modifier.fillMaxHeight(), tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { buttons() }
        }
    }
}

/**
 * One tool slot on the rail. The face is the group's currently [selected] tool: a **tap** activates
 * it, a **long-press** opens a picker over the group's other members, and picking one both re-faces
 * the slot (persisted via [onSelect]) and activates it. A single-member group has nothing to pick,
 * so it skips the menu entirely. [active] tints the slot when the editor's live tool is in this
 * group, which is what makes the rail read as a row of radio buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolGroupButton(
    group: ToolGroup,
    selected: EditorTool,
    active: Boolean,
    onTool: (EditorTool) -> Unit,
    onSelect: (EditorTool) -> Unit,
    lineStyle: LineStyle? = null,
    onLineStyle: ((LineStyle) -> Unit)? = null,
    fill: Int? = null,
    onFill: ((Int?) -> Unit)? = null,
    width: Float? = null,
    widthSlots: List<Float>? = null,
    onWidth: ((Float) -> Unit)? = null,
    onRedefineSlot: ((Int, Float) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var editingWidth by remember { mutableStateOf(-1) }
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Box(
            modifier = Modifier
                .size(ToolbarButtonSize)
                .clip(CircleShape)
                .then(if (active) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .combinedClickable(
                    onClick = { onTool(selected) },
                    onLongClick = { open = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(selected.icon, contentDescription = "Tool: ${selected.label}", tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading(group.label)
            for (member in group.tools) {
                DropdownMenuItem(
                    text = { Text(member.label) },
                    leadingIcon = { Icon(member.icon, contentDescription = null) },
                    trailingIcon = {
                        if (member == selected) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onSelect(member); open = false; onTool(member) },
                )
            }
            // Line group gets style and width sections
            if (group.id == "line" && lineStyle != null && onLineStyle != null) {
                MenuHeading("Line style")
                for ((style, label) in LINE_STYLE_LABELS) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            if (style == lineStyle) Icon(Icons.Filled.Check, contentDescription = "selected")
                        },
                        onClick = { onLineStyle(style); open = false },
                    )
                }
            }
            if (group.id == "line" && fill != null && onFill != null) {
                MenuHeading("Fill")
                FillControlsMini(fill, onFill) { open = false }
            }
            if (group.id == "line" && width != null && widthSlots != null && onWidth != null && onRedefineSlot != null) {
                MenuHeading("Tip size")
                widthSlots.forEachIndexed { i, pt ->
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onWidth(pt); open = false },
                                onLongClick = { editingWidth = i; open = false },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.nexopp.ui.WidthDot(pt, (widthSlots + width).maxOrNull()!!, MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.padding(horizontal = 12.dp))
                        Text("${com.nexopp.ui.ptLabel(pt)} pt")
                        if (pt == width) {
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            Icon(Icons.Filled.Check, contentDescription = "selected")
                        }
                    }
                }
                Text(
                    "Tap to pick · long-press to resize",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Width slot editor dialog
        if (editingWidth in widthSlots!!.indices) {
            com.nexopp.ui.WidthSlotSliderDialog(
                label = com.nexopp.ui.PEN_WIDTH_LABELS[editingWidth],
                initial = widthSlots[editingWidth],
                onConfirm = { newPt -> onRedefineSlot?.invoke(editingWidth, newPt); editingWidth = -1 },
                onDismiss = { editingWidth = -1 },
            )
        }
    }
}
