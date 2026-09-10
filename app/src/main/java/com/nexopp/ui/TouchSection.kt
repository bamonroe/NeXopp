package com.nexopp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexopp.render.TapWindow
import com.nexopp.render.TouchAction

/**
 * What your fingers do on the canvas, as opposed to the stylus: whether a finger draws at all, and
 * what each tap gesture invokes.
 *
 * A one-finger tap is only read as a gesture when that finger isn't drawing — with the Hand tool, or
 * with "Finger draws" off. Two-finger gestures are always live, because a second finger already
 * means pan/zoom rather than ink. Everything the stylus itself does (barrel button, pressure, hover)
 * is in the Stylus section instead.
 */
@Composable
fun TouchSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SwitchRow(
        title = "Finger draws",
        subtitle = "Off: fingers only pan/zoom and never use any tool — stylus only. With it off, " +
            "the one-finger tap gestures below work under every tool, not just the Hand tool.",
        checked = settings.fingerDraws,
        onCheckedChange = { onChange(settings.copy(fingerDraws = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    TouchActionGroup(
        title = "Double-tap",
        subtitle = "Two quick taps with one finger, in the same spot.",
        selected = settings.touchDoubleTap,
        onSelect = { onChange(settings.copy(touchDoubleTap = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    TouchActionGroup(
        title = "Triple-tap",
        subtitle = "Three quick taps with one finger. Setting this to anything but Nothing makes " +
            "the double-tap wait out the tap window below before it fires, so a triple-tap doesn't " +
            "set off the double-tap on its way past.",
        selected = settings.touchTripleTap,
        onSelect = { onChange(settings.copy(touchTripleTap = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    TouchActionGroup(
        title = "Two-finger tap",
        subtitle = "Two fingers down together, held still, and straight back up — a tap that never " +
            "became a pan or a pinch. Fires wherever they land, whatever the tool.",
        selected = settings.touchTwoFingerTap,
        onSelect = { onChange(settings.copy(touchTwoFingerTap = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    TouchActionGroup(
        title = "Two-finger double-tap",
        subtitle = "Two of those in a row, in the same place — and likewise delaying the single " +
            "two-finger tap by one tap window while it waits to see.",
        selected = settings.touchTwoFingerDoubleTap,
        onSelect = { onChange(settings.copy(touchTwoFingerDoubleTap = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "Tap window",
        subtitle = "How long a run of taps may take: taps further apart than this count as separate " +
            "gestures. Raise it if a double-tap of yours keeps registering as two single taps.",
        options = TapWindow.CHOICES,
        selected = settings.touchTapWindowMs,
        label = TapWindow::label,
        onSelect = { onChange(settings.copy(touchTapWindowMs = it)) },
    )
}

/**
 * One gesture's picker: what it is, what it means, and the action it fires. Every gesture offers the
 * same [TouchAction] list, so nothing can be bound in one place but not another.
 *
 * A drop-down rather than the radio group the other sections use: with this many actions and four
 * gestures to bind, radio buttons would run to dozens of rows of scrolling.
 */
@Composable
private fun TouchActionGroup(
    title: String,
    subtitle: String,
    selected: TouchAction,
    onSelect: (TouchAction) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(subtitle, style = MaterialTheme.typography.bodySmall)
    DropdownRow(
        label = "Does",
        options = TouchAction.entries.toList(),
        selected = selected,
        optionLabel = { it.label },
        onSelect = onSelect,
    )
}
