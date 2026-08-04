package com.xopp.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.LayerInfo

/** How many panes the editor can show. Two: one document per half of the screen. */
const val PANE_COUNT = 2

/**
 * Everything the chrome mirrors out of **one** canvas.
 *
 * The canvas is a [DrawingSurfaceView] living outside the Compose tree, so it pushes its state up
 * through callbacks; this is where that lands. [EditorScreen] keeps one instance per pane, so in
 * split view the two documents keep separate zoom, page, layer and undo state — the toolbar simply
 * reads whichever pane is active (see [com.xopp.android.panes.EditorPane]).
 */
class PaneState {
    var surface by mutableStateOf<DrawingSurfaceView?>(null)
    var zoom by mutableStateOf(1f)
    var pageCount by mutableStateOf(1)

    /** How many pages are picked in the overview grid — drives the bulk-delete entries in the Pages menu. */
    var selectedPages by mutableStateOf(0)
    var copiedPages by mutableStateOf(0)

    /** Overview edit mode: off means the grid is display/navigation only (see `DrawingSurfaceView`). */
    var pagesEditMode by mutableStateOf(false)
    var currentPage by mutableStateOf(0)

    // Vertical scroll geometry (content px) fed from the surface, driving the right-edge scroll thumb.
    var scrollY by mutableStateOf(0f)
    var contentHeight by mutableStateOf(0f)
    var viewportHeight by mutableStateOf(0f)

    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var hasSelection by mutableStateOf(false)
    /** "Group" mode: further marquees/taps add to the selection instead of replacing it. */
    var groupMode by mutableStateOf(false)
    var hasTextSelection by mutableStateOf(false)
    var hasClipboard by mutableStateOf(false)
    var layers by mutableStateOf<List<LayerInfo>>(emptyList())
    var backgroundStyle by mutableStateOf<String?>(null)
    var pageSize by mutableStateOf<Pair<Double, Double>?>(null)
}
