package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xopp.android.format.model.Tool
import kotlin.math.roundToInt

/** One selectable pen width, labelled for the chip and carrying the pt value the canvas uses. */
data class WidthOption(val label: String, val pt: Float)

/** The pen palette offered in the chrome. Colours are opaque ARGB; highlighter renders them translucent. */
val PEN_COLORS: List<Int> = listOf(
    0xFF000000.toInt(), // black
    0xFFE00000.toInt(), // red
    0xFF2060E0.toInt(), // blue
    0xFF1E9E1E.toInt(), // green
    0xFFF08000.toInt(), // orange
    0xFFF0D000.toInt(), // yellow
)

val PEN_WIDTHS: List<WidthOption> = listOf(
    WidthOption("S", 0.85f),
    WidthOption("M", 1.5f),
    WidthOption("L", 2.6f),
)

/**
 * The editor's interaction modes. PEN/HIGHLIGHTER/ERASER map to the document [Tool]; HAND is a
 * view-only mode where one finger pans the canvas instead of drawing (useful on multi-page or
 * zoomed documents).
 */
enum class EditorTool { PEN, HIGHLIGHTER, ERASER, HAND }

private data class ToolInfo(val tool: EditorTool, val label: String, val icon: ImageVector)

private val TOOLS: List<ToolInfo> = listOf(
    ToolInfo(EditorTool.PEN, "Pen", Icons.Filled.Create),
    ToolInfo(EditorTool.HIGHLIGHTER, "Highlighter", Icons.Filled.Brush),
    ToolInfo(EditorTool.ERASER, "Eraser", Icons.Filled.Delete),
    ToolInfo(EditorTool.HAND, "Hand (pan)", Icons.Filled.PanTool),
)

/**
 * The bottom control bar: five buttons — Tool, Colour, Size, Zoom, Pages — each opening a small
 * [DropdownMenu] anchored to its own button (never full-screen, never centred). [EditorScreen]
 * pushes the picked value onto the [com.xopp.android.render.DrawingSurfaceView].
 */
@Composable
fun BottomToolbar(
    tool: EditorTool,
    onTool: (EditorTool) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
    width: Float,
    onWidth: (Float) -> Unit,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    pageCount: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPopupButton(tool, onTool)
            ColorPopupButton(color, onColor)
            SizePopupButton(width, onWidth)
            ZoomPopupButton(zoom, onZoomIn, onZoomOut, onZoomReset)
            PagesPopupButton(pageCount, onAddPage, onRemovePage)
        }
    }
}

@Composable
private fun ToolPopupButton(tool: EditorTool, onTool: (EditorTool) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = TOOLS.first { it.tool == tool }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(current.icon, contentDescription = "Tool: ${current.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (info in TOOLS) {
                DropdownMenuItem(
                    text = { Text(info.label) },
                    leadingIcon = { Icon(info.icon, contentDescription = null) },
                    trailingIcon = {
                        if (info.tool == tool) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onTool(info.tool); open = false },
                )
            }
        }
    }
}

@Composable
private fun ColorPopupButton(color: Int, onColor: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Circle, contentDescription = "Colour", tint = Color(color))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (c in PEN_COLORS) {
                    Swatch(color = c, selected = c == color, onClick = { onColor(c); open = false })
                }
            }
        }
    }
}

@Composable
private fun SizePopupButton(width: Float, onWidth: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = PEN_WIDTHS.firstOrNull { it.pt == width }?.label ?: "?"
    Box {
        TextButton(onClick = { open = true }) {
            Text("Size $current")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (w in PEN_WIDTHS) {
                DropdownMenuItem(
                    text = { Text("${w.label}  (${w.pt} pt)") },
                    trailingIcon = {
                        if (w.pt == width) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { onWidth(w.pt); open = false },
                )
            }
        }
    }
}

@Composable
private fun ZoomPopupButton(zoom: Float, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onZoomReset: () -> Unit) {
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

@Composable
private fun PagesPopupButton(pageCount: Int, onAddPage: () -> Unit, onRemovePage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Description, contentDescription = "Pages")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add page") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddPage(); open = false },
            )
            DropdownMenuItem(
                text = { Text("Remove page") },
                leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null) },
                enabled = pageCount > 1,
                onClick = { onRemovePage(); open = false },
            )
            Text(
                "$pageCount page${if (pageCount == 1) "" else "s"}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000)
    val ringWidth = if (selected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(ringWidth, ring, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {}
}
