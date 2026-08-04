package com.xopp.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The PALETTE settings page: a diagram of the two-ring radial palette with every slot tappable, so
 * a slot can be picked here and then assigned. The diagram is drawn from the same geometry the
 * canvas uses ([editorSlots]), so what the editor shows is what the pen will see.
 */
@Composable
fun PaletteSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    var selected by remember { mutableStateOf<RadialSlot?>(null) }
    Text("Radial palette", style = MaterialTheme.typography.titleMedium)
    Text(
        "The menu a barrel double-click opens at the pen tip. Tap a slot to select it.",
        style = MaterialTheme.typography.bodySmall,
    )
    PaletteDiagram(
        palette = settings.radialPalette,
        selected = selected,
        onSelect = { selected = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Column {
        Text("Selected slot", style = MaterialTheme.typography.bodyLarge)
        Text(selected.describe(settings.radialPalette), style = MaterialTheme.typography.bodySmall)
    }
}

/** How the selected slot reads in prose: which ring, which position, and what it currently holds. */
internal fun RadialSlot?.describe(palette: RadialPalette): String {
    if (this == null) return "None — tap a slot in the diagram above."
    val ringName = if (ring == RadialRing.INNER) "Inner" else "Outer"
    val action = palette[this]
    val holds = if (action == null) "empty" else action.face().glyph.ifEmpty { "colour" }
    return "$ringName ring, slot ${index + 1} of ${ring.slotCount} — $holds."
}

/** The tappable two-ring diagram. Stateless: it draws [palette] and reports taps through [onSelect]. */
@Composable
private fun PaletteDiagram(
    palette: RadialPalette,
    selected: RadialSlot?,
    onSelect: (RadialSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(palette)
    val select by rememberUpdatedState(onSelect)
    val outline = MaterialTheme.colorScheme.outline
    val filled = MaterialTheme.colorScheme.secondaryContainer
    val glyphColor = MaterialTheme.colorScheme.onSecondaryContainer
    val highlight = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier.aspectRatio(1f).pointerInput(Unit) {
            detectTapGestures { offset ->
                val edge = minOf(size.width, size.height).toFloat()
                current.editorSlotAt(offset.x, offset.y, edge)?.let(select)
            }
        },
    ) {
        val edge = minOf(size.width, size.height)
        drawRings(edge, outline)
        for (mark in palette.editorSlots(edge)) {
            drawSlotMark(mark, mark.slot == selected, measurer, outline, filled, glyphColor, highlight)
        }
    }
}

/** The three guide circles — dead zone, inner ring, outer ring — that frame the slots. */
private fun DrawScope.drawRings(edge: Float, outline: Color) {
    val scale = paletteEditorScale(edge)
    val center = Offset(edge / 2f, edge / 2f)
    val geometry = RadialPaletteGeometry()
    for (r in listOf(geometry.deadZoneRadius, geometry.innerRingRadius, geometry.outerRingRadius)) {
        drawCircle(outline.copy(alpha = 0.35f), radius = r * scale, center = center, style = Stroke(1.dp.toPx()))
    }
}

/** One slot: a filled disc (or its swatch) when assigned, a dashed outline when empty. */
private fun DrawScope.drawSlotMark(
    mark: PaletteEditorSlot,
    isSelected: Boolean,
    measurer: TextMeasurer,
    outline: Color,
    filled: Color,
    glyphColor: Color,
    highlight: Color,
) {
    val center = Offset(mark.center.x, mark.center.y)
    val face = mark.action?.face()
    if (face == null) {
        drawCircle(
            color = outline,
            radius = mark.radius,
            center = center,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
        )
    } else {
        val swatch = face.swatchArgb
        drawCircle(swatch?.let { Color(it) } ?: filled, radius = mark.radius, center = center)
        if (swatch == null && face.glyph.isNotEmpty()) {
            val layout = measurer.measure(face.glyph, TextStyle(color = glyphColor, fontSize = 12.sp))
            drawText(
                layout,
                topLeft = Offset(
                    center.x - layout.size.width / 2f,
                    center.y - layout.size.height / 2f,
                ),
            )
        }
    }
    if (isSelected) {
        drawCircle(highlight, radius = mark.radius + 3.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
    }
}
