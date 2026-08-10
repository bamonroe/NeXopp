package com.nexopp.ui

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

private val THUMB_GRAB_WIDTH = 28.dp
private val THUMB_WIDTH = 6.dp
private val OUTSERT_WIDTH = 18.dp
private val OUTSERT_HEIGHT = 38.dp
private val THUMB_MIN_HEIGHT = 44.dp
private const val FADE_MS = 1400L

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
    if (maxScroll <= 0f || viewportPx <= 0f) return

    val density = LocalDensity.current
    val minThumbPx = with(density) { THUMB_MIN_HEIGHT.toPx() }

    val thumbHeightPx = (viewportPx * viewportPx / totalHeightPx).coerceIn(minThumbPx, viewportPx)
    val travelPx = (viewportPx - thumbHeightPx).coerceAtLeast(0f)
    val scrollFraction = (scrollY / maxScroll).coerceIn(0f, 1f)
    val thumbTopPx = scrollFraction * travelPx

    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }

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

    Box(modifier = modifier.fillMaxSize()) {
        ScrollThumbBand(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTopPx.roundToInt()) },
            thumbHeightPx = thumbHeightPx,
            travelPx = travelPx,
            maxScroll = maxScroll,
            bandTop = thumbTopPx,
            alpha = alpha,
            onScrollTo = onScrollTo,
        )
        if (dragging && pageCount > 0) {
            PageBubble(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (thumbTopPx + thumbHeightPx / 2f - with(density) { 16.dp.toPx() }).roundToInt()) }
                    .padding(end = THUMB_GRAB_WIDTH + 4.dp),
                currentPage = currentPage,
                pageCount = pageCount,
            )
        }
    }
}

@Composable
private fun ScrollThumbBand(
    modifier: Modifier,
    thumbHeightPx: Float,
    travelPx: Float,
    maxScroll: Float,
    bandTop: Float,
    alpha: Float,
    onScrollTo: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var grip by remember { mutableStateOf(0f) }

    val travel by rememberUpdatedState(travelPx)
    val max by rememberUpdatedState(maxScroll)
    val top by rememberUpdatedState(bandTop)

    Box(
        modifier = modifier
            .width(THUMB_GRAB_WIDTH)
            .heightPx(thumbHeightPx)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        grip = start.y
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    if (travel > 0f) {
                        val newTop = top + change.position.y - grip
                        onScrollTo((newTop / travel * max).coerceIn(0f, max))
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        ThumbVisual(
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = 3.dp)
                .width(OUTSERT_WIDTH),
            alpha = alpha,
        )
    }
}

@Composable
private fun ThumbVisual(
    modifier: Modifier,
    alpha: Float,
) {
    val thumbColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val path = thumbPath(
            w = size.width,
            h = size.height,
            barW = THUMB_WIDTH.toPx(),
            bulgeH = OUTSERT_HEIGHT.toPx(),
        )
        drawPath(path, color = thumbColor.copy(alpha = alpha))
    }
}

@Composable
private fun PageBubble(
    modifier: Modifier,
    currentPage: Int,
    pageCount: Int,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = pageLabel(currentPage, pageCount),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun thumbPath(w: Float, h: Float, barW: Float, bulgeH: Float): Path {
    val r = barW / 2f
    val barLeft = w - barW
    val bulge = bulgeH.coerceIn(0f, h - 2f * r)
    val bulgeTop = (h - bulge) / 2f
    val bulgeBot = bulgeTop + bulge
    val yMid = (bulgeTop + bulgeBot) / 2f
    val k = 0.5f
    return Path().apply {
        moveTo(barLeft, r)
        lineTo(barLeft, bulgeTop)
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
        lineTo(barLeft, h - r)
        arcTo(Rect(barLeft, h - barW, w, h), 180f, -180f, false)
        lineTo(w, r)
        arcTo(Rect(barLeft, 0f, w, barW), 0f, -180f, false)
        close()
    }
}

private fun Modifier.heightPx(px: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val h = px.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    },
)
