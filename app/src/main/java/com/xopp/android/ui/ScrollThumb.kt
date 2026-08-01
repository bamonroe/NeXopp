package com.xopp.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
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
/**
 * The thumb is drawn as one continuous silhouette: a thin bar with a straight right edge hugging the
 * canvas edge and rounded caps, whose left outline swells out in a smooth symmetric bell centred on
 * the thumb so there's an obvious, finger-sized grip to grab (the thin bar alone reads as
 * decoration). [OUTSERT_WIDTH] is how far the bell's peak reaches left of the canvas edge, and
 * [OUTSERT_HEIGHT] the bell's vertical span. Purely visual — the whole [THUMB_GRAB_WIDTH] band
 * already catches touches.
 */
private val OUTSERT_WIDTH = 18.dp
private val OUTSERT_HEIGHT = 38.dp
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
            // The visible thumb: one continuous shape — a thin capsule bar hugging the right edge with
            // a smooth bell bulge swelling out of its centre. Right-aligned inside the wider
            // (invisible) grab band; the canvas is as wide as the bulge so the bell's peak reaches its
            // left edge. Shares the thumb's alpha so it fades in step.
            val thumbColor = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 3.dp)
                    .width(OUTSERT_WIDTH),
            ) {
                val path = thumbPath(
                    w = size.width,
                    h = size.height,
                    barW = THUMB_WIDTH.toPx(),
                    bulgeH = OUTSERT_HEIGHT.toPx(),
                )
                drawPath(path, color = thumbColor.copy(alpha = alpha))
            }
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

/**
 * Build the thumb silhouette in the canvas's px space: a bar of width [barW] whose straight right
 * edge sits at `x = w` with semicircular caps, and whose left edge swells out to `x = 0` (the canvas
 * edge) in a smooth symmetric bell of vertical extent [bulgeH] centred in [h]. The bell is two
 * mirrored cubic Béziers with vertical tangents at the bar joins and at the peak, so it departs and
 * rejoins the bar edge smoothly. [bulgeH] is clamped so the straight bar segments above and below it
 * never go negative on a short thumb.
 */
private fun thumbPath(w: Float, h: Float, barW: Float, bulgeH: Float): Path {
    val r = barW / 2f
    val barLeft = w - barW
    val bulge = bulgeH.coerceIn(0f, h - 2f * r)
    val bulgeTop = (h - bulge) / 2f
    val bulgeBot = bulgeTop + bulge
    val yMid = (bulgeTop + bulgeBot) / 2f
    // How far each cubic's control point runs along the vertical (as a fraction of the bell's half
    // height) before the curve turns out to the peak. Both handles meet vertically at the peak and at
    // the bar joins, so their lengths must sum to <= 1 or the outline reverses into a concave notch;
    // 0.5 is the fullest clean value and gives a smooth symmetric bell.
    val k = 0.5f
    return Path().apply {
        moveTo(barLeft, r)
        // Down the bar's left edge to where the bell begins.
        lineTo(barLeft, bulgeTop)
        // Swoop out to the leftmost peak, then back to the bar.
        cubicTo(
            barLeft, bulgeTop + k * (yMid - bulgeTop),
            0f, yMid - k * (yMid - bulgeTop),
            0f, yMid,
        )
        cubicTo(
            0f, yMid + k * (bulgeBot - yMid),
            barLeft, bulgeBot - k * (bulgeBot - yMid),
            barLeft, bulgeBot,
        )
        // Down to the bottom cap, around it, up the straight right edge, and around the top cap.
        lineTo(barLeft, h - r)
        arcTo(Rect(barLeft, h - barW, w, h), 180f, -180f, false)
        lineTo(w, r)
        arcTo(Rect(barLeft, 0f, w, barW), 0f, -180f, false)
        close()
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
