package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** How narrow either half may be dragged, as a fraction of the whole width. */
private const val MIN_FRACTION = 0.15f

/** The grab area down the middle — wide enough for a finger, per the tap-target sizing in [TabStrip]. */
private val HANDLE_WIDTH = 24.dp

/** The hairline actually painted inside the handle. */
private val HANDLE_LINE = 2.dp

/**
 * Two panes side by side with a draggable bar down the middle.
 *
 * [fraction] is the width given to [first] (0..1 of the whole, minus the handle); dragging the bar
 * reports a new value through [onFraction] so the caller owns the split position. The handle is a
 * finger-wide strip around a hairline rule, so it can be grabbed without a stylus.
 */
@Composable
fun SplitLayout(
    fraction: Float,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val totalPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        Row(modifier = Modifier.fillMaxSize()) {
            first(Modifier.fillMaxHeight().weight(fraction.coerceIn(MIN_FRACTION, 1f - MIN_FRACTION)))
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(HANDLE_WIDTH)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .semantics { contentDescription = "Resize split" }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            onFraction((fraction + delta / totalPx).coerceIn(MIN_FRACTION, 1f - MIN_FRACTION))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(HANDLE_LINE)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            second(Modifier.fillMaxHeight().weight((1f - fraction).coerceIn(MIN_FRACTION, 1f - MIN_FRACTION)))
        }
    }
}
