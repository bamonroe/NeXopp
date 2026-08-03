package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Everything the [TabStrip] needs, bundled so [EditorScreen]'s parameter list stays readable.
 *
 * The tabs themselves live in `com.xopp.android.tabs` and are driven by the host activity — this is
 * only the flattened view of them the chrome draws.
 */
data class TabsUiState(
    /** Tab titles in strip order (the file name, or "Untitled"). */
    val titles: List<String> = emptyList(),
    /**
     * Per-tab dot colour (packed ARGB), or null for no dot — set only on tabs that are one of
     * several views of the *same* document, so duplicates are told apart from same-named files.
     * Same colour = same document. See `com.xopp.android.tabs.DocColors`.
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
    Row(
        modifier = modifier
            .height(TAB_STRIP_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState()),
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
            )
        }
        IconButton(onClick = state.onNew, modifier = Modifier.size(TAB_TOUCH_TARGET)) {
            Icon(Icons.Filled.Add, contentDescription = "New document", modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * One tab: its (elided) title, and a close button that is only offered on the selected tab.
 *
 * A long press opens the pane menu — move this document to the other half of a split, or mirror a
 * second view of it there — so both live where the tab does rather than in the app bar. A [dotColor]
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
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .heightIn(min = TAB_TOUCH_TARGET)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceVariant)
            .combinedClickable(onClick = onSelect, onLongClick = { menuOpen = true })
            .padding(start = 16.dp, end = if (selected) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Move to other view") },
                onClick = { menuOpen = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("Mirror on other view") },
                onClick = { menuOpen = false; onMirror() },
            )
        }
        if (dotColor != null) {
            // Marks this tab as one of two views of the same document; the matching view carries the
            // same colour, which is what distinguishes it from a different file of the same name.
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
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp, max = 180.dp),
        )
        if (selected) {
            // A full 48dp target of its own, so closing the tab never lands on "select" by mistake.
            Box(
                modifier = Modifier.size(TAB_TOUCH_TARGET).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close $title",
                    modifier = Modifier.size(20.dp),
                    tint = colors.onSecondaryContainer,
                )
            }
        }
    }
}

/** The mirrored-document dot: big enough to read as a colour, small enough not to crowd the title. */
private val TAB_DOT_SIZE = 8.dp

/** Material's minimum comfortable touch target; every tap area in the strip is at least this big. */
private val TAB_TOUCH_TARGET = 48.dp

/** Strip height: one touch target plus the 4dp breathing room above and below each chip. */
private val TAB_STRIP_HEIGHT = TAB_TOUCH_TARGET + 8.dp
