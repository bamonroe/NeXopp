package com.xopp.android.tabs

import com.xopp.android.format.SaveFormat
import com.xopp.android.format.model.Document

/**
 * One document open in the editor — a tab.
 *
 * A tab owns its whole editing state *except* the live canvas: only the active tab's [document] is
 * loaded into the `DrawingSurfaceView`, and switching tabs snapshots the surface back into the tab
 * it is leaving. Everything here is what a tab needs to be restored later, either from another tab
 * or from a cold app start (see [TabStore]).
 */
data class OpenTab(
    /** Stable identity, also the snapshot file name stem under [TabStore]. Never reused. */
    val id: String,
    /** What the tab strip shows — the file name, or "Untitled" for a document never saved. */
    val title: String,
    /** The document itself; for the active tab this is a snapshot taken at the last switch/persist. */
    val document: Document,
    /** The `content://` URI this document came from, or null when it has never been opened/saved. */
    val uri: String? = null,
    /** The sticky save format for this document (see [SaveFormat]) — per-tab, not per-app. */
    val format: SaveFormat = SaveFormat.ORIGINAL,
    /** On-disk path of the PDF backing this document's `pdf` backgrounds, or null when there is none. */
    val pdfPath: String? = null,
    /** The page that was in view, so re-selecting the tab lands where you left it. */
    val page: Int = 0,
)

/** The whole set of open tabs plus which one is showing — what survives an app restart. */
data class TabSession(
    val tabs: List<OpenTab>,
    val activeIndex: Int,
)
