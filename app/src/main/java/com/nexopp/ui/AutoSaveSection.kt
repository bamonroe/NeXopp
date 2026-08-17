package com.nexopp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexopp.io.AutoSavePolicy

/**
 * The two autosave timers: save after a pause in the writing, and save every so often regardless.
 * Both are off by default — an autosave writes over the real `.xopp`, so it is opt-in. The behaviour
 * behind them is [AutoSavePolicy].
 */
@Composable
fun AutoSaveSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    OptionGroup(
        title = "Save after inactivity",
        subtitle = "Once you stop drawing for this long, the document writes itself back to its " +
            "file. Each new stroke restarts the countdown.",
        options = AutoSavePolicy.IDLE_CHOICES,
        selected = settings.autoSaveIdleSeconds,
        label = AutoSavePolicy::label,
        onSelect = { onChange(settings.copy(autoSaveIdleSeconds = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Save every",
        subtitle = "A save on this interval even while you keep drawing, so a long unbroken " +
            "session can't lose more than this much work.",
        options = AutoSavePolicy.INTERVAL_CHOICES,
        selected = settings.autoSaveIntervalSeconds,
        label = AutoSavePolicy::label,
        onSelect = { onChange(settings.copy(autoSaveIntervalSeconds = it)) },
    )
}
