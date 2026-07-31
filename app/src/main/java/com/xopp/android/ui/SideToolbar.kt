package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
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
 * view-only pan mode; SELECT rubber-band-selects objects to move/delete them; TEXT/IMAGE/TEXIMAGE
 * are authoring modes where a canvas tap places (or edits) that element
 * (see [com.xopp.android.render.PlaceKind]).
 */
enum class EditorTool { PEN, HIGHLIGHTER, ERASER, HAND, SELECT, TEXT, IMAGE, TEXIMAGE }

private data class ToolInfo(val tool: EditorTool, val label: String, val icon: ImageVector)

private val TOOLS: List<ToolInfo> = listOf(
    ToolInfo(EditorTool.PEN, "Pen", Icons.Filled.Create),
    ToolInfo(EditorTool.HIGHLIGHTER, "Highlighter", Icons.Filled.Brush),
    ToolInfo(EditorTool.ERASER, "Eraser", Icons.Filled.Delete),
    ToolInfo(EditorTool.HAND, "Hand (pan)", Icons.Filled.PanTool),
    ToolInfo(EditorTool.SELECT, "Select", Icons.Filled.HighlightAlt),
    ToolInfo(EditorTool.TEXT, "Text", Icons.Filled.TextFields),
    ToolInfo(EditorTool.IMAGE, "Image", Icons.Filled.Image),
    ToolInfo(EditorTool.TEXIMAGE, "LaTeX", Icons.Filled.Functions),
)

/**
 * The vertical control rail down the left edge: Tool, Colour, Size, Zoom, and a page navigator —
 * each a button opening a small [DropdownMenu] anchored to its own button (which opens to the right
 * of the rail). [EditorScreen] pushes the picked value onto the
 * [com.xopp.android.render.DrawingSurfaceView].
 */
@Composable
fun SideToolbar(
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
    currentPage: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onGoToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxHeight(), tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .padding(4.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToolPopupButton(tool, onTool)
            ColorPopupButton(color, onColor)
            SizePopupButton(width, onWidth)
            ZoomPopupButton(zoom, onZoomIn, onZoomOut, onZoomReset)
            PagesPopupButton(pageCount, currentPage, onAddPage, onRemovePage, onGoToPage)
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
            Text(current)
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

/**
 * The page navigator: shows the current page, jumps to the previous/next page, and adds or removes
 * a page. [currentPage] is 0-based; the label and jump targets present it 1-based.
 */
@Composable
private fun PagesPopupButton(
    pageCount: Int,
    currentPage: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Description, contentDescription = "Pages")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onGoToPage(currentPage - 1) },
                    enabled = currentPage > 0,
                ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page") }
                Text(
                    "Page ${(currentPage + 1).coerceAtMost(pageCount)} / $pageCount",
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = { onGoToPage(currentPage + 1) },
                    enabled = currentPage < pageCount - 1,
                ) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next page") }
            }
            DropdownMenuItem(
                text = { Text("Add page") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddPage() },
            )
            DropdownMenuItem(
                text = { Text("Remove page") },
                leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null) },
                enabled = pageCount > 1,
                onClick = { onRemovePage() },
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
