package com.xopp.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

    HorizontalDivider(Modifier.padding(vertical = 12.dp))

    SwitchRow(
        title = "Use system colours",
        subtitle = "On Android 12+ takes colours from your wallpaper. Off uses the app's fixed purple.",
        checked = settings.dynamicColor,
        onCheckedChange = { onChange(settings.copy(dynamicColor = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Page counter position", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Which corner of the canvas the always-visible \"page X of Y\" badge sits in.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    DropdownRow(
        label = "Vertical",
        options = PageCounterVertical.values().toList(),
        selected = settings.pageCounterVertical,
        optionLabel = { it.label },
        onSelect = { onChange(settings.copy(pageCounterVertical = it)) },
    )
    DropdownRow(
        label = "Horizontal",
        options = PageCounterHorizontal.values().toList(),
        selected = settings.pageCounterHorizontal,
        optionLabel = { it.label },
        onSelect = { onChange(settings.copy(pageCounterHorizontal = it)) },
    )
}
