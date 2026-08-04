package com.xopp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xopp.android.render.GuideKind
import kotlin.math.roundToInt

@Composable
internal fun ZoomPopupButton(zoom: Float, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onZoomReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${(zoom * 100).roundToInt()}%")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onZoomOut) { Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out") }
                TextButton(onClick = onZoomReset) { Text("${(zoom * 100).roundToInt()}%") }
                IconButton(onClick = onZoomIn) { Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in") }
            }
        }
    }
}

/**
 * Paper-style labels for the background pop-up, paired with the `<background style=…>` value written
 * to the `.xopp` file. Names match desktop Xournal++ verbatim so files round-trip; the renderer draws
 * each in [com.xopp.android.render.BackgroundRenderer].
 */
private val BACKGROUND_STYLES: List<Pair<String, String>> = listOf(
    "plain" to "Plain",
    "lined" to "Lined",
    "ruled" to "Ruled",
    "graph" to "Graph",
    "dotted" to "Dotted",
)

/**
 * The page-background chooser: sets the current page's paper style (plain/lined/ruled/graph/dotted).
 * [style] is the current page's style, or null when the page is a PDF/pixmap (no solid sheet to
 * restyle) — in which case the items are disabled.
 */
@Composable
internal fun BackgroundPopupButton(style: String?, onBackgroundStyle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.GridOn, contentDescription = "Page background")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in BACKGROUND_STYLES) {
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = style != null,
                    trailingIcon = {
                        if (value == style) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onBackgroundStyle(value); open = false },
                )
            }
        }
    }
}

/**
 * The drawing-guide pop-up: lay a setsquare or a compass on the page, or take it off again. The
 * guide is an input aid — a finger drags it around and re-poses it by its tip handle, and anything
 * drawn near its edge is ruled onto that edge. Nothing about it is written to the `.xopp` file.
 */
@Composable
internal fun GuidePopupButton(kind: GuideKind, onKind: (GuideKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.ChangeHistory,
                contentDescription = "Drawing guides",
                tint = if (kind == GuideKind.NONE) LocalContentColor.current
                else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Drawing guide")
            for (option in GuideKind.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = { if (option == kind) Icon(Icons.Filled.Check, contentDescription = "selected") },
                    onClick = { onKind(option); open = false },
                )
            }
        }
    }
}

/**
 * The audio slot: start/stop the recording that new strokes are stamped against, stop playback, and
 * nominate the folder sidecar `.wav` files are kept in. The button turns primary-coloured while the
 * microphone is live, because a forgotten recording is the one mistake here that costs the user
 * something (battery, privacy, a pile of stamped strokes).
 */
@Composable
internal fun AudioPopupButton(state: AudioUiState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = "Audio",
                tint = if (state.recording || state.playing) MaterialTheme.colorScheme.primary
                else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Audio")
            DropdownMenuItem(
                text = { Text(if (state.recording) "Stop recording" else "Record") },
                leadingIcon = {
                    Icon(
                        if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = null,
                    )
                },
                onClick = { state.onToggleRecord(); open = false },
            )
            DropdownMenuItem(
                text = { Text("Stop playback") },
                enabled = state.playing,
                leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                onClick = { state.onStopPlayback(); open = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.folderChosen) "Change audio folder…" else "Choose audio folder…") },
                onClick = { state.onChooseFolder(); open = false },
            )
            Text(
                text = if (state.folderChosen) {
                    "Recordings are saved beside your .xopp files."
                } else {
                    "Recordings stay in the app until you choose a folder next to your .xopp files."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(220.dp),
            )
        }
    }
}

/** Everything the [AudioPopupButton] needs, bundled so the rail's parameter list stays readable. */
data class AudioUiState(
    val recording: Boolean = false,
    val playing: Boolean = false,
    val folderChosen: Boolean = false,
    val onToggleRecord: () -> Unit = {},
    val onStopPlayback: () -> Unit = {},
    val onChooseFolder: () -> Unit = {},
)
