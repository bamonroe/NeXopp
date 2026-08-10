package com.nexopp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexopp.render.BarrelAction
import com.nexopp.render.BarrelDoubleAction
import com.nexopp.render.PaletteInvocation
import com.nexopp.render.PressureSensitivity
import com.nexopp.render.StrokePrecision

/** Stylus behaviours: palm rejection, hover preview, barrel-button action and pressure "feel". */
@Composable
fun StylusSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SwitchRow(
        title = "Finger draws",
        subtitle = "Off: fingers only pan/zoom and never use any tool — stylus only.",
        checked = settings.fingerDraws,
        onCheckedChange = { onChange(settings.copy(fingerDraws = it)) },
    )
    SwitchRow(
        title = "Hover preview",
        subtitle = "Show a ring where a hovering stylus will land.",
        checked = settings.showHover,
        onCheckedChange = { onChange(settings.copy(showHover = it)) },
    )
    SwitchRow(
        title = "Palette haptics",
        subtitle = "Tick as a radial-palette flick crosses slots, and confirm when it commits.",
        checked = settings.paletteHaptics,
        onCheckedChange = { onChange(settings.copy(paletteHaptics = it)) },
    )
    SwitchRow(
        title = "Close palette on select",
        subtitle = "Dismiss the radial palette as soon as a slot is picked, instead of leaving " +
            "it open until you tap outside it.",
        checked = settings.paletteCloseOnSelect,
        onCheckedChange = { onChange(settings.copy(paletteCloseOnSelect = it)) },
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
        title = "Barrel double-click",
        subtitle = "Action for a rapid double-click of the side-button, with the tip off the glass.",
        options = BarrelDoubleAction.values().toList(),
        selected = settings.barrelDoubleAction,
        label = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) },
        onSelect = { onChange(settings.copy(barrelDoubleAction = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Open the palette by touch",
        subtitle = "Touch gesture that summons the radial palette, for a stylus with no side " +
            "button. The side button itself is set above, under Barrel double-click.",
        options = PaletteInvocation.values().toList(),
        selected = settings.paletteInvocation,
        label = { it.label },
        onSelect = { onChange(settings.copy(paletteInvocation = it)) },
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
    OptionGroup(
        title = "Stroke precision",
        subtitle = "How much pen detail a stroke keeps. Higher draws rounder curves on a big, " +
            "high-density screen; lower keeps files smaller.",
        options = StrokePrecision.values().toList(),
        selected = settings.strokePrecision,
        label = { it.label },
        onSelect = { onChange(settings.copy(strokePrecision = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SwitchRow(
        title = "Shape recognition",
        subtitle = "Snap a finished freehand stroke to the shape it resembles — line, arrow, " +
            "circle, rectangle, triangle or polyline. Anything unrecognised stays as drawn.",
        checked = settings.recognizeShapes,
        onCheckedChange = { onChange(settings.copy(recognizeShapes = it)) },
    )
}
