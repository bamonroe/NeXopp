package com.nexopp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Everything the [TabStrip] needs, bundled so [EditorScreen]'s parameter list stays readable.
 *
 * The tabs themselves live in `com.nexopp.tabs` and are driven by the host activity — this is
 * only the flattened view of them the chrome draws.
 */
data class TabsUiState(
    /** Tab titles in strip order (the file name, or "Untitled"). */
    val titles: List<String> = emptyList(),
    /**
     * Per-tab dot colour (packed ARGB), or null for no dot — set only on tabs that are one of
     * several views of the *same* document, so duplicates are told apart from same-named files.
     * Same colour = same document. See `com.nexopp.tabs.DocColors`.
     */
    val dotColors: List<Int?> = emptyList(),
    val activeIndex: Int = 0,
    val onSelect: (Int) -> Unit = {},
    val onClose: (Int) -> Unit = {},
    val onNew: () -> Unit = {},
    /** Long-press action: hand this tab to the other pane, opening split view if it is closed. */
    val onMove: (Int) -> Unit = {},
    /** Long-press action: open a second copy of this tab in the other pane, keeping this one. */
    val onMirror: (Int) -> Unit = {},
    /** Drag action: the tab at the first index now sits at the second one. */
    val onReorder: (Int, Int) -> Unit = { _, _ -> },
    /**
     * The tab overview is opening: the live canvas is copied back into the showing tab, so its
     * preview isn't a stale snapshot taken at the last tab switch.
     */
    val onOverview: () -> Unit = {},
    /**
     * Rasterise the page tab [Int] is showing, [Int] pixels wide, and hand the bitmap to the callback
     * on the main thread — null if it can't be drawn. Asynchronous because a tab restored from a cold
     * start still has to be parsed (gzip + XML), which is far too slow to do in a tap.
     */
    val preview: (Int, Int, (Bitmap?) -> Unit) -> Unit = { _, _, done -> done(null) },
)

/**
 * The row of open documents above the editor. Selecting a tab swaps the whole document on the
 * canvas; the trailing "+" opens a fresh blank one.
 *
 * Always shown once a document is open — including the single-document case — so "new tab", "close"
 * and "switch" sit in the same place no matter how many documents are loaded. Only an empty session
 * (no tabs at all) draws nothing.
 */
@Composable
fun TabStrip(state: TabsUiState, modifier: Modifier = Modifier) {
    if (state.titles.isEmpty()) return
    // Drag-reorder state lives here, not in the chip: a drag walks the tab past its neighbours, so the
    // slot the gesture started in stops being the dragged tab after the first swap. [dragIndex] is
    // where the dragged tab sits *now*, and [dragOffset] is how far past its slot the finger is.
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    // Where the selected chip sits inside the (scrollable) content, so a tab selected from elsewhere —
    // or one carried past the edge by a reorder drag — is scrolled back into view instead of stranded.
    var activeBounds by remember { mutableStateOf(0f to 0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activeBounds, viewportWidth, scroll.maxValue) {
        val (left, width) = activeBounds
        if (viewportWidth <= 0f || width <= 0f) return@LaunchedEffect
        val target = when {
            left < scroll.value -> left
            left + width > scroll.value + viewportWidth -> left + width - viewportWidth
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier = modifier
            .height(TAB_STRIP_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged { viewportWidth = it.width.toFloat() }
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.titles.forEachIndexed { index, title ->
            TabChip(
                title = title,
                dotColor = state.dotColors.getOrNull(index),
                selected = index == state.activeIndex,
                onSelect = { state.onSelect(index) },
                onClose = { state.onClose(index) },
                onMove = { state.onMove(index) },
                onMirror = { state.onMirror(index) },
                dragOffset = if (index == dragIndex) dragOffset else 0f,
                onDragStart = { dragIndex = index; dragOffset = 0f },
                onDrag = { dx, width ->
                    if (dragIndex >= 0) {
                        dragOffset += dx
                        // One whole chip of travel = one slot; swap and keep the rest of the offset so a
                        // long drag walks the tab across several neighbours in one gesture.
                        while (dragOffset > width && dragIndex < state.titles.lastIndex) {
                            state.onReorder(dragIndex, dragIndex + 1)
                            dragIndex++
                            dragOffset -= width
                        }
                        while (dragOffset < -width && dragIndex > 0) {
                            state.onReorder(dragIndex, dragIndex - 1)
                            dragIndex--
                            dragOffset += width
                        }
                        dragOffset = dragOffset.coerceIn(-width, width)
                    }
                },
                onDragEnd = { dragIndex = -1; dragOffset = 0f },
                onBounds = { left, width -> activeBounds = left to width },
            )
        }
        IconButton(onClick = state.onNew, modifier = Modifier.size(TAB_TOUCH_TARGET)) {
            Icon(Icons.Filled.Add, contentDescription = "New document", modifier = Modifier.size(TAB_NEW_ICON))
        }
    }
}

/**
 * One tab: its (elided) title, and a close button offered on every tab, selected or not.
 *
 * A long press opens the pane menu — move this document to the other half of a split, or mirror a
 * second view of it there — so both live where the tab does rather than in the app bar. Dragging it
 * sideways instead reorders it within this strip. A [dotColor]
 * marks a tab that is one of several views of one document.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    title: String,
    dotColor: Int?,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onMove: () -> Unit,
    onMirror: () -> Unit,
    dragOffset: Float,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, chipWidth: Float) -> Unit,
    onDragEnd: () -> Unit,
    onBounds: (left: Float, width: Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val drag = rememberUpdatedState(Triple(onDragStart, onDrag, onDragEnd))
    var chipWidth by remember { mutableFloatStateOf(1f) }
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = TAB_CHIP_VERTICAL_PADDING)
            .heightIn(min = TAB_TOUCH_TARGET)
            .graphicsLayer { translationX = dragOffset }
            .onSizeChanged { chipWidth = it.width.toFloat().coerceAtLeast(1f) }
            .onGloballyPositioned {
                if (selected) onBounds(it.positionInParent().x, it.size.width.toFloat())
            }
            .tabChipInteractions(selected, onSelect, { menuOpen = true }, drag, chipWidth)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabChipMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, onMove = onMove, onMirror = onMirror)
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(TAB_DOT_SIZE)
                    .clip(CircleShape)
                    .background(Color(dotColor)),
            )
        }
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp, max = 180.dp),
        )
        TabCloseButton(title = title, selected = selected, onClose = onClose)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.tabChipInteractions(
    selected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    drag: androidx.compose.runtime.State<Triple<() -> Unit, (Float, Float) -> Unit, () -> Unit>>,
    chipWidth: Float,
): Modifier {
    return this
        .clip(RoundedCornerShape(8.dp))
        .background(
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
        .combinedClickable(onClick = onSelect, onLongClick = onLongClick)
        .pointerInput(selected) {
            if (!selected) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { drag.value.first },
                onDragEnd = { drag.value.third },
                onDragCancel = { drag.value.third },
            ) { change, dx ->
                change.consume()
                drag.value.second(dx, chipWidth)
            }
        }
}

@Composable
private fun TabChipMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onMirror: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Move to other view") },
            onClick = { onDismiss(); onMove() },
        )
        DropdownMenuItem(
            text = { Text("Mirror on other view") },
            onClick = { onDismiss(); onMirror() },
        )
    }
}

@Composable
private fun TabCloseButton(
    title: String,
    selected: Boolean,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(TAB_TOUCH_TARGET).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "Close $title",
            modifier = Modifier.size(TAB_CLOSE_ICON),
            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
    }
}

/** The mirrored-document dot: big enough to read as a colour, small enough not to crowd the title. */
private val TAB_DOT_SIZE = 6.dp

/**
 * The strip's tap-target size. Below Material's 48dp minimum on purpose: the strip is deliberately
 * compact so it steals as little canvas as it can, and every target here is a wide chip or an icon
 * with clear space around it rather than a dense cluster.
 */
private val TAB_TOUCH_TARGET = 36.dp

/** The close/new icon glyphs, scaled to sit inside the compact [TAB_TOUCH_TARGET]. */
private val TAB_CLOSE_ICON = 16.dp
private val TAB_NEW_ICON = 18.dp

/** Vertical breathing room above and below each chip. */
private val TAB_CHIP_VERTICAL_PADDING = 3.dp

/** Strip height: one touch target plus the breathing room above and below each chip. */
private val TAB_STRIP_HEIGHT = TAB_TOUCH_TARGET + TAB_CHIP_VERTICAL_PADDING * 2
