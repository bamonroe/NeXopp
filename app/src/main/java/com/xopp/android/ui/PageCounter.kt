package com.xopp.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The badge's corner as a Compose [Alignment], from the two configured axes (Appearance settings).
 */
fun pageCounterAlignment(
    vertical: PageCounterVertical,
    horizontal: PageCounterHorizontal,
): Alignment = when (vertical) {
    PageCounterVertical.TOP -> when (horizontal) {
        PageCounterHorizontal.LEFT -> Alignment.TopStart
        PageCounterHorizontal.CENTER -> Alignment.TopCenter
        PageCounterHorizontal.RIGHT -> Alignment.TopEnd
    }
    PageCounterVertical.CENTER -> when (horizontal) {
        PageCounterHorizontal.LEFT -> Alignment.CenterStart
        PageCounterHorizontal.CENTER -> Alignment.Center
        PageCounterHorizontal.RIGHT -> Alignment.CenterEnd
    }
    PageCounterVertical.BOTTOM -> when (horizontal) {
        PageCounterHorizontal.LEFT -> Alignment.BottomStart
        PageCounterHorizontal.CENTER -> Alignment.BottomCenter
        PageCounterHorizontal.RIGHT -> Alignment.BottomEnd
    }
}

/**
 * The always-visible "page X of Y" badge.
 *
 * It sits over the canvas rather than in the top bar so it survives full-page mode, where the whole
 * bar is hidden. [currentPage] is 0-based (as [PaneState.currentPage] is); the label adds the 1.
 */
@Composable
fun PageCounter(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 0) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        tonalElevation = 3.dp,
    ) {
        Text(
            text = "${(currentPage + 1).coerceIn(1, pageCount)} / $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
