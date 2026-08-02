package com.xopp.android.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Format an opaque ARGB int as a `#RRGGBB` hex string (upper-case; alpha dropped). */
fun argbToHex(argb: Int): String =
    String.format(java.util.Locale.US, "#%06X", argb and 0xFFFFFF)

/** Parse a `#RRGGBB` (leading `#` optional) hex string to an opaque ARGB int, or null if malformed. */
fun hexToArgb(hex: String): Int? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6) return null
    val rgb = s.toLongOrNull(16)?.toInt() ?: return null
    return rgb or 0xFF000000.toInt()
}

/**
 * The arbitrary-colour picker opened by long-pressing the palette's custom slot: a saturation/value
 * [SatValSquare] over a hue slider, plus a two-way `#RRGGBB` hex field and a live preview. Colours are
 * always opaque; the parent persists the result via [onConfirm].
 */
@Composable
fun CustomColorPickerDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv0 = remember { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by remember { mutableStateOf(hsv0[0]) }
    var sat by remember { mutableStateOf(hsv0[1]) }
    var value by remember { mutableStateOf(hsv0[2]) }
    var hexText by remember { mutableStateOf(argbToHex(initial)) }

    // Push a new H/S/V onto state and keep the hex field mirroring it (sliders/square are authoritative).
    fun apply(h: Float, s: Float, v: Float) {
        hue = h; sat = s; value = v
        hexText = argbToHex(Color.hsv(h, s, v).toArgb())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SatValSquare(hue, sat, value) { s, v -> apply(hue, s, v) }
                Text("Hue")
                Slider(value = hue, onValueChange = { apply(it, sat, value) }, valueRange = 0f..360f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.hsv(hue, sat, value))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { txt ->
                            hexText = txt
                            hexToArgb(txt)?.let { c ->
                                val out = FloatArray(3)
                                AndroidColor.colorToHSV(c, out)
                                hue = out[0]; sat = out[1]; value = out[2]
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(Color.hsv(hue, sat, value).toArgb()) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A saturation (X) / value (Y) selection square for the current [hue]: white→hue left-to-right,
 * transparent→black top-to-bottom, with a ring marking [sat]/[value]. Tap or drag reports the picked
 * saturation/value (both 0..1) via [onChange].
 */
@Composable
private fun SatValSquare(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onChange(
                        (pos.x / size.width).coerceIn(0f, 1f),
                        (1f - pos.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        drawRect(Color.hsv(hue, 1f, 1f))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val cx = sat.coerceIn(0f, 1f) * size.width
        val cy = (1f - value.coerceIn(0f, 1f)) * size.height
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
    }
}
