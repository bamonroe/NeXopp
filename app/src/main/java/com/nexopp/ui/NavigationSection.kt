package com.nexopp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexopp.render.MomentumCurve
import com.nexopp.render.ScrollLock

/** Canvas navigation: momentum scrolling (strength and curve), panning sensitivity and scroll lock. */
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

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Scroll lock",
        subtitle = "Stop one-finger pans drifting off one axis: locking horizontal scrolling lets " +
            "a drag move up and down only, locking vertical lets it move side to side only. " +
            "A two-finger pan is never locked, and going to a page, a search hit or a zoom still " +
            "lands where it should. " +
            "Also on the top bar beside undo, and assignable to a radial-palette slot.",
        options = ScrollLock.entries.toList(),
        selected = settings.scrollLock,
        label = { it.label },
        onSelect = { onChange(settings.copy(scrollLock = it)) },
    )
}
