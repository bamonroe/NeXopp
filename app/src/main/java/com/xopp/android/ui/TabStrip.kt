package com.xopp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val activeIndex: Int = 0,
    val onSelect: (Int) -> Unit = {},
    val onClose: (Int) -> Unit = {},
    val onNew: () -> Unit = {},
)

/**
 * The row of open documents above the editor. Selecting a tab swaps the whole document on the
 * canvas; the trailing "+" opens a fresh blank one.
 *
 * Hidden when only one document is open, so the single-document case keeps every pixel of drawing
 * area it has today — the strip appears the moment a second tab exists.
 */
@Composable
fun TabStrip(state: TabsUiState, modifier: Modifier = Modifier) {
    if (state.titles.size < 2) return
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
                selected = index == state.activeIndex,
                onSelect = { state.onSelect(index) },
                onClose = { state.onClose(index) },
            )
        }
        IconButton(onClick = state.onNew, modifier = Modifier.size(TAB_STRIP_HEIGHT)) {
            Icon(Icons.Filled.Add, contentDescription = "New document", modifier = Modifier.size(16.dp))
        }
    }
}

/** One tab: its (elided) title, and a close button that is only offered on the selected tab. */
@Composable
private fun TabChip(title: String, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceVariant)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = if (selected) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        if (selected) {
            Box(
                modifier = Modifier.size(28.dp).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close $title",
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSecondaryContainer,
                )
            }
        }
    }
}

private val TAB_STRIP_HEIGHT = 32.dp
