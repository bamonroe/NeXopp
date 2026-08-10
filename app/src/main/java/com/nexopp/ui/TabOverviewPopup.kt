package com.nexopp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexopp.render.PageThumbnail

/** How wide each preview is rasterised — a grid cell at typical tablet density. */
private const val THUMB_WIDTH_PX = 240

/**
 * The tab overview: a toolbar button that opens a grid of every open tab, each shown as the page it
 * was left on. Tapping a preview switches to that tab and closes the grid — the visual counterpart to
 * the [TabStrip]'s titles, for when several tabs are named alike (or "Untitled").
 *
 * Previews are requested once, when the grid opens, and land asynchronously ([TabsUiState.preview]) —
 * a tab restored from a cold start has to be parsed before it can be drawn, so its card shows blank
 * under its title until its thumbnail arrives.
 */
@Composable
internal fun TabOverviewButton(tabs: TabsUiState) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { tabs.onOverview(); open = true }) {
        Icon(Icons.Filled.GridView, contentDescription = "Tab overview")
    }
    if (!open) return
    // One slot per tab, filled in as each preview lands. Keyed on the tab count so a tab opened or
    // closed while the grid is up doesn't leave the previews shifted off their titles.
    val thumbs = remember(tabs.titles.size) { mutableStateListOf<Bitmap?>().apply { repeat(tabs.titles.size) { add(null) } } }
    LaunchedEffect(thumbs) {
        tabs.titles.indices.forEach { i ->
            tabs.preview(i, THUMB_WIDTH_PX) { bitmap -> if (i < thumbs.size) thumbs[i] = bitmap }
        }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        title = { Text("Open tabs") },
        text = {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 120.dp)) {
                itemsIndexed(tabs.titles) { index, title ->
                    TabOverviewCell(
                        title = title,
                        thumbnail = thumbs.getOrNull(index),
                        active = index == tabs.activeIndex,
                        onClick = { open = false; tabs.onSelect(index) },
                    )
                }
            }
        },
    )
}

/** One tab in the grid: its page preview (or a blank card) above its title. */
@Composable
private fun TabOverviewCell(
    title: String,
    thumbnail: Bitmap?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val outline =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier.padding(4.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f) // portrait, close enough to A4/Letter for an empty placeholder
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (active) 2.dp else 1.dp, outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
