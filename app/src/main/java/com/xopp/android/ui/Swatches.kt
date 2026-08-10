package com.xopp.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The default box size for a width dot swatch, and the largest dot that fits comfortably inside it. */
private val DEFAULT_DOT_BOX = 28.dp
private val DEFAULT_DOT_MAX = 22.dp

/** The smallest a width dot is drawn, so a hair-thin slot is still a visible target. */
private val DEFAULT_DOT_MIN = 5.dp

/**
 * A filled dot standing for a pen tip of [pt], sized *relatively*: the widest slot on offer
 * ([maxPt]) draws at [maxDotSize] and everything else in proportion, so the set reads as a size
 * ladder and the biggest still sits inside its box instead of overflowing it.
 *
 * Relative scaling is used because absolute scaling (e.g. against [PEN_WIDTH_MAX]) can overflow
 * the box when the actual maximum width in use is smaller than the theoretical maximum.
 *
 * @param pt The pen width in points this dot represents.
 * @param maxPt The maximum pen width in points currently in use, used as the scaling reference.
 * @param tint The fill color of the dot.
 * @param boxSize The size of the box this dot is drawn in.
 * @param bordered Whether to draw a 1dp outline border around the dot.
 */
@Composable
internal fun WidthDot(
    pt: Float,
    maxPt: Float,
    tint: Color,
    boxSize: Dp = DEFAULT_DOT_BOX,
    bordered: Boolean = false,
) {
    val maxDotSize = boxSize - 6.dp
    val minDotSize = boxSize - 23.dp
    val diameter = if (maxPt <= 0f) minDotSize
    else (maxDotSize * (pt / maxPt)).coerceIn(minDotSize, maxDotSize)
    Box(modifier = Modifier.size(boxSize), contentAlignment = Alignment.Center) {
        val dotModifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(tint)
        val finalModifier = if (bordered) {
            dotModifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        } else {
            dotModifier
        }
        Box(modifier = finalModifier)
    }
}

/**
 * A colour swatch: a filled circle with a selection ring. Passing [onLongClick] makes it respond to a
 * long-press (used by the editable custom slot); [editable] overlays a small pencil to mark that slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    editable: Boolean = false,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val ringWidth = if (selected) 3.dp else 1.dp
    val clickModifier = if (onLongClick != null)
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    else Modifier.clickable(onClick = onClick)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(ringWidth, ring, CircleShape)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (editable) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Edit custom colour",
                tint = if (Color(color).luminance() < 0.5f) Color.White else Color.Black,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
