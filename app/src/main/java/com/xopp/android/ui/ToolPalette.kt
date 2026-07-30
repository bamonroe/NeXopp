package com.xopp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xopp.android.format.model.Tool

/** Material 3 tool palette: pick pen / highlighter / eraser. */
@Composable
fun ToolPalette(
    selected: Tool,
    onSelect: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolChip("Pen", Icons.Filled.Create, Tool.PEN, selected, onSelect)
            ToolChip("Highlighter", Icons.Filled.Brush, Tool.HIGHLIGHTER, selected, onSelect)
            ToolChip("Eraser", Icons.Filled.Delete, Tool.ERASER, selected, onSelect)
        }
    }
}

@Composable
private fun ToolChip(
    label: String,
    icon: ImageVector,
    tool: Tool,
    selected: Tool,
    onSelect: (Tool) -> Unit,
) {
    FilterChip(
        selected = selected == tool,
        onClick = { onSelect(tool) },
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = label) },
    )
}
