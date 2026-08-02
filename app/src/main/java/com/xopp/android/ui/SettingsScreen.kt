package com.xopp.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The settings sections. Each is a clickable row on the settings index that opens as its own page,
 * so a single screen never has to carry every preference at once.
 */
enum class SettingsSection(val title: String, val summary: String) {
    STYLUS("Stylus", "Finger drawing, hover preview, barrel button, pressure feel."),
    EDITOR("Editor", "Default tool and where the tool rail is docked."),
    TOOLBAR("Toolbar", "Which rail buttons appear, and in what order."),
    NAVIGATION("Navigation", "Momentum scrolling and panning sensitivity."),
}

/**
 * Settings, as an index of sections plus one page per section: the index lists the sections and
 * tapping one pushes its own page (with a back arrow returning to the index; back from the index
 * leaves settings entirely). Edits are pushed live to the canvas and persisted via [SettingsStore]
 * (see `MainActivity`). Reached from the top-bar menu.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    val open = section
    if (open == null) {
        SettingsPage(title = "Settings", onBack = onBack) {
            SettingsSection.values().forEach { entry ->
                SectionRow(entry) { section = entry }
                HorizontalDivider()
            }
        }
    } else {
        SettingsPage(title = open.title, onBack = { section = null }) {
            when (open) {
                SettingsSection.STYLUS -> StylusSection(settings, onChange)
                SettingsSection.EDITOR -> EditorSection(settings, onChange)
                SettingsSection.TOOLBAR -> ToolbarSection(settings, onChange)
                SettingsSection.NAVIGATION -> NavigationSection(settings, onChange)
            }
        }
    }
}

/** The shared chrome for the index and every section page: a titled bar, back arrow, scrolling body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            body()
        }
    }
}

/** One tappable row on the index: the section's name, what it covers, and a chevron. */
@Composable
private fun SectionRow(section: SettingsSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(section.title, style = MaterialTheme.typography.bodyLarge)
            Text(section.summary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
