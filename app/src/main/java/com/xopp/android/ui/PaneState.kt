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
    /** The canvas this pane wraps; null until the surface is created. */
    var surface by mutableStateOf<DrawingSurfaceView?>(null)
    
    /** Current zoom factor (1.0 = 100%, fit-to-width on first load). */
    var zoom by mutableStateOf(1f)
    
    /** Total number of pages in the document. */
    var pageCount by mutableStateOf(1)

    /** How many pages are picked in the overview grid — drives the bulk-delete entries in the Pages menu. */
    var selectedPages by mutableStateOf(0)
    
    /** How many pages are on the page clipboard (after copy in the overview). */
    var copiedPages by mutableStateOf(0)

    /** Overview edit mode: off means the grid is display/navigation only (see `DrawingSurfaceView`). */
    var pagesEditMode by mutableStateOf(false)
    
    /** Current page index, 0-based. */
    var currentPage by mutableStateOf(0)

    // Vertical scroll geometry (content px) fed from the surface, driving the right-edge scroll thumb.
    /** Current vertical scroll offset in pixels. */
    var scrollY by mutableStateOf(0f)
    /** Total height of the scrollable content in pixels. */
    var contentHeight by mutableStateOf(0f)
    /** Height of the visible viewport in pixels. */
    var viewportHeight by mutableStateOf(0f)

    /** Whether undo is available. */
    var canUndo by mutableStateOf(false)
    /** Whether redo is available. */
    var canRedo by mutableStateOf(false)
    /** Whether there is an active element selection. */
    var hasSelection by mutableStateOf(false)
    /** Whether there is an active text selection (caret inside a text box). */
    var hasTextSelection by mutableStateOf(false)
    /** Whether there is content on the clipboard (cut or copied selection/pages). */
    var hasClipboard by mutableStateOf(false)
    /** Whether the search bar is open. */
    var searchOpen by mutableStateOf(false)
    /** Current search query text. */
    var searchQuery by mutableStateOf("")
    /** Current match index (1-based) in the search results. */
    var searchCurrent by mutableStateOf(0)
    /** Total number of search matches found. */
    var searchTotal by mutableStateOf(0)
    /** List of visible layers on the current page. */
    var layers by mutableStateOf<List<LayerInfo>>(emptyList())
    /** Background style of the current page (plain/lined/ruled/graph/dotted), or null for PDF backgrounds. */
    var backgroundStyle by mutableStateOf<String?>(null)
    /** Page size as (widthPt, heightPt) in points, or null when unavailable. */
    var pageSize by mutableStateOf<Pair<Double, Double>?>(null)
}
