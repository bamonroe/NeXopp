package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One selectable pen width, labelled for the chip and carrying the pt value the canvas uses. */
data class WidthOption(val label: String, val pt: Float)

/** The pen palette offered in the chrome. Colours are opaque ARGB; highlighter renders them translucent. */
val PEN_COLORS: List<Int> = listOf(
    0xFF000000.toInt(), // black
    0xFFE00000.toInt(), // red
    0xFF2060E0.toInt(), // blue
    0xFF1E9E1E.toInt(), // green
    0xFFF08000.toInt(), // orange
    0xFFF0D000.toInt(), // yellow
)

val PEN_WIDTHS: List<WidthOption> = listOf(
    WidthOption("S", 0.85f),
    WidthOption("M", 1.5f),
    WidthOption("L", 2.6f),
)

/**
 * Colour swatches and width chips for the pen/highlighter. Selecting one calls back with the new
 * ARGB colour or pt width; [EditorScreen] pushes those onto the [com.xopp.android.render.DrawingSurfaceView].
 * The row scrolls horizontally so it never clips on a narrow phone.
 */
@Composable
fun PenSettings(
    selectedColor: Int,
    onColor: (Int) -> Unit,
    selectedWidth: Float,
    onWidth: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (color in PEN_COLORS) {
                Swatch(color = color, selected = color == selectedColor, onClick = { onColor(color) })
            }
            Spacer(Modifier.width(4.dp))
            for (w in PEN_WIDTHS) {
                FilterChip(
                    selected = w.pt == selectedWidth,
                    onClick = { onWidth(w.pt) },
                    label = { Text(w.label) },
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000)
    val ringWidth = if (selected) 3.dp else 1.dp
    Row(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(ringWidth, ring, CircleShape)
            .clickable(onClick = onClick),
    ) {}
}
