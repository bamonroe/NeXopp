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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.Momentum
import com.xopp.android.render.PanSensitivity
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
            MomentumSlider(
                value = settings.momentum,
                onChange = { onChange(settings.copy(momentum = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            PanSensitivitySlider(
                value = settings.panSensitivity,
                onChange = { onChange(settings.copy(panSensitivity = it)) },
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

/**
 * The momentum-strength control: a continuous slider from [Momentum.OFF] to [Momentum.MAX] whose
 * value is snapped to the [Momentum.STEP] grid. Left continuous (no discrete stops) so the wide
 * 0..10 range doesn't render a thicket of tick marks. 0 reads "Off" (a released pan stops dead);
 * every other value shows its factor.
 */
@Composable
private fun MomentumSlider(value: Float, onChange: (Float) -> Unit) {
    Text("Momentum scrolling", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    Text(
        "How far the canvas keeps gliding after you flick a pan. 0 turns momentum off; 1 is normal.",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
    Text("Panning sensitivity", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    Text(
        "How far the canvas moves as you pan. 1 matches your finger; lower is slower, higher is faster; 0 turns panning off.",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
