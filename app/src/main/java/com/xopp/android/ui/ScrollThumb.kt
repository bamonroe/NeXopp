package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Width of the grab band on the right edge, and the visual thumb bar inside it. */
private val THUMB_GRAB_WIDTH = 28.dp
private val THUMB_WIDTH = 6.dp
/** Smallest the thumb ever shrinks to, so a very long document still leaves a grabbable target. */
private val THUMB_MIN_HEIGHT = 44.dp
/** How long the thumb stays bright after the last scroll before fading back to its faint idle look. */
private const val FADE_MS = 1400L

/**
 * A PDF-style right-hand scroll thumb overlaid on the canvas. It tracks the document's vertical
 * scroll position (fed from [DrawingSurfaceView.onScrollChanged] as [scrollY] / [totalHeightPx] /
 * [viewportPx], all content px) and lets the user **drag** the thumb to page quickly through a long
 * document via [onScrollTo]. Purely a navigation affordance — no `.xopp` document state — so nothing
 * round-trips. Hidden entirely when the whole document already fits.
 *
 * The touch target is the thumb *band* itself — a small region that tracks the scroll position —
 * not the whole right edge, so a stylus can still draw over the right margin of the page everywhere
 * except the thumb. The thumb stays faintly visible when idle (so it's discoverable and always
 * grabbable), brightens for a moment after any scroll, and is brightest while being dragged; a
 * page-number bubble appears beside it during a drag.
 *
 * The overlay must fill the same region as the canvas so its px height equals [viewportPx]; place it
 * as a sibling of the [DrawingSurfaceView] `AndroidView` in a shared [Box].
 */
@Composable
fun ScrollThumb(
    scrollY: Float,
    totalHeightPx: Float,
    viewportPx: Float,
    currentPage: Int,
    pageCount: Int,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxScroll = (totalHeightPx - viewportPx).coerceAtLeast(0f)
    // Nothing to scroll: the document fits the viewport, so there's no thumb to show.
    if (maxScroll <= 0f || viewportPx <= 0f) return

    val density = LocalDensity.current
    val minThumbPx = with(density) { THUMB_MIN_HEIGHT.toPx() }

    val thumbHeightPx = (viewportPx * viewportPx / totalHeightPx).coerceIn(minThumbPx, viewportPx)
    val travelPx = (viewportPx - thumbHeightPx).coerceAtLeast(0f)
    val scrollFraction = (scrollY / maxScroll).coerceIn(0f, 1f)
    val thumbTopPx = scrollFraction * travelPx

    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    // Brighten the thumb for a moment after each scroll, then fade it back to its faint idle look.
    LaunchedEffect(scrollY, totalHeightPx, dragging) {
        recentlyScrolled = true
        if (!dragging) {
            delay(FADE_MS)
            recentlyScrolled = false
        }
    }
    val alpha = when {
        dragging -> 0.9f
        recentlyScrolled -> 0.55f
        else -> 0.24f
    }

    // Latest geometry captured for the drag gesture, so each drag delta maps to the right scroll delta.
    val travel by rememberUpdatedState(travelPx)
    val max by rememberUpdatedState(maxScroll)
    val curScroll by rememberUpdatedState(scrollY)

    // A full-size overlay so the page bubble has room to render left of the thumb; only the thumb
    // band carries a pointer input, so the rest of the canvas stays transparent to touch.
    Box(modifier = modifier.fillMaxSize()) {
        // The grab band — a thumb-sized target at the current scroll position. It always carries the
        // pointer input so the thumb is reliably grabbable, but it's only ~a thumb tall, so it steals
        // touches from just that band and the rest of the page's right margin stays drawable.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .width(THUMB_GRAB_WIDTH)
                .heightPx(thumbHeightPx)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        if (travel > 0f) {
                            val delta = drag.y / travel * max
                            onScrollTo((curScroll + delta).coerceIn(0f, max))
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            // The visible thumb bar, right-aligned inside the wider (invisible) grab band.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 3.dp)
                    .width(THUMB_WIDTH)
                    .clip(RoundedCornerShape(THUMB_WIDTH / 2))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
        // While dragging, a page-number bubble tracks the thumb's vertical centre (sibling of the
        // band, so it can extend into the full canvas width to the left of it).
        if (dragging && pageCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (thumbTopPx + thumbHeightPx / 2f - with(density) { 16.dp.toPx() }).roundToInt()) }
                    .padding(end = THUMB_GRAB_WIDTH + 4.dp),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = "${(currentPage + 1).coerceAtMost(pageCount)} / $pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Give a node an exact px height (the thumb length is computed in content px, not dp). */
private fun Modifier.heightPx(px: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val h = px.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    },
)
