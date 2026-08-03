package com.xopp.android.panes

import com.xopp.android.format.SaveFormat
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.tabs.TabManager
import com.xopp.android.tabs.TabStore

/**
 * One editing pane: a canvas plus the set of documents open in it.
 *
 * In split view the editor shows **two** of these side by side, and each one keeps its own tab
 * session, its own canvas (hence its own scroll/zoom and undo history) and its own sticky save
 * format. Whichever pane the user last touched is the *active* one; every menu and toolbar action
 * in [com.xopp.android.MainActivity] is routed to it, which is what lets the rest of the app carry
 * on being written against "the" document.
 *
 * Panes are persisted independently — each has its own [TabStore] directory — so a split session
 * comes back the way it was left.
 */
class EditorPane(
    /** Where this pane's open tabs (unsaved edits included) are cached across restarts. */
    val store: TabStore,
) {
    /** The live canvas, once Compose has created it. Null before the first composition. */
    var surface: DrawingSurfaceView? = null

    /** The documents open in this pane and which one is showing. */
    val tabs = TabManager()

    /**
     * The sticky save format for this pane. "Save As" sets it; every later plain Save reuses it.
     * Opening a document adopts the format it was stored in.
     */
    var saveFormat: SaveFormat = SaveFormat.ORIGINAL

    /** The file name last chosen in the Save As dialog for this pane; reused by plain Save. */
    var pendingSaveName: String = "document.xopp"

    /** Write this pane's session out. Cheap enough to call on every tab change. */
    fun persist() = store.save(tabs.session())
}
