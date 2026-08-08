package com.xopp.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xopp.android.render.MomentumCurve

/** Canvas navigation: momentum scrolling (strength and curve) and panning sensitivity. */
@Composable
fun NavigationSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    MomentumSlider(
        value = settings.momentum,
        onChange = { onChange(settings.copy(momentum = it)) },
    )
    OptionGroup(
        title = "Momentum curve",
        subtitle = "How sharply a faster flick coasts farther: Linear is even, " +
            "Exponential rewards fast swipes the most.",
        options = MomentumCurve.values().toList(),
        selected = settings.momentumCurve,
        label = { it.label },
        onSelect = { onChange(settings.copy(momentumCurve = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    PanSensitivitySlider(
        value = settings.panSensitivity,
        onChange = { onChange(settings.copy(panSensitivity = it)) },
    )
}
