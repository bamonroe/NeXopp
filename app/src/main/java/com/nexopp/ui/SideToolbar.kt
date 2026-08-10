package com.nexopp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.IconButton
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
                ToolGroupButton(
                    group = group,
                    selected = group.selected(toolGroupSelections),
                    active = tool in group.tools,
                    onTool = onTool,
                    onSelect = { picked ->
                        onToolGroupSelections(group.withSelection(toolGroupSelections, picked))
                        onTool(picked)
                    },
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
                    .padding(4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    } else {
        Surface(modifier = modifier.fillMaxHeight(), tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
) {
    var open by remember { mutableStateOf(false) }
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(if (active) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .combinedClickable(
                    onClick = { onTool(selected) },
                    onLongClick = { if (group.tools.size > 1) open = true },
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
        }
    }
}
