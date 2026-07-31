package com.xopp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.MomentumStrength
import com.xopp.android.render.PressureSensitivity

/**
 * Full-screen settings page for the input layer: stylus behaviours (palm rejection, barrel-button
 * action, hover preview, pressure "feel") plus editor preferences (the default tool a document opens
 * in). Edits are pushed live to the canvas and persisted via [SettingsStore] (see `MainActivity`).
 * Reached from the top-bar menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Stylus")

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
                title = "Pressure sensitivity",
                subtitle = "How firmly you press to thicken the line.",
                options = PressureSensitivity.values().toList(),
                selected = settings.sensitivity,
                label = { it.label },
                onSelect = { onChange(settings.copy(sensitivity = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Editor")
            OptionGroup(
                title = "Default tool",
                subtitle = "Which tool is active when a document opens.",
                options = DEFAULT_TOOL_CHOICES,
                selected = settings.defaultTool,
                label = { it.label },
                onSelect = { onChange(settings.copy(defaultTool = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            OptionGroup(
                title = "Momentum scrolling",
                subtitle = "How far the canvas keeps gliding after you flick a pan.",
                options = MomentumStrength.values().toList(),
                selected = settings.momentum,
                label = { it.label },
                onSelect = { onChange(settings.copy(momentum = it)) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
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
