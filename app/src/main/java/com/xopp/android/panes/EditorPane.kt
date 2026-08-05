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

    /**
     * Write this pane's session out — called on every tab change and on the way to the background.
     *
     * The session is read here, on the caller's (main) thread, so it is a consistent snapshot; the
     * writing itself is gzip XML per open document, far too slow for the main thread, so it is queued
     * onto [writer]. One single-threaded queue keeps the writes in the order they were asked for, so
     * the last state asked for is the state left on disk.
     */
    fun persist() {
        val session = tabs.session()
        writer.execute { store.save(session) }
    }

    /**
     * Block until the queued session writes have finished — used when the app is going away and the
     * snapshot has to be on disk before the process can be killed.
     */
    fun awaitPersist(timeoutMs: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute(done::countDown)
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
}
