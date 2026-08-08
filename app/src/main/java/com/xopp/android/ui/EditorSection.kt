package com.xopp.android.ui

import androidx.compose.runtime.Composable

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
