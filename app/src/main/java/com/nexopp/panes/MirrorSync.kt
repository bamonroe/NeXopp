package com.nexopp.panes

import android.os.Handler
import android.os.Looper
import com.nexopp.format.model.Document
import com.nexopp.tabs.OpenTab

/**
 * Keeps the several views of one document in step.
 *
 * The same document may be open in both split panes (long-press a tab → "Mirror on other view"), and
 * the two are *views*, not copies: each pane keeps its own scroll, zoom and page, but there is one
 * document behind them, so a stroke drawn on one side appears on the other at once.
 *
 * Tabs say which views belong together through [OpenTab.docKey] — mirroring carries the key over, so
 * every tab holding that key, in either pane, is a view of the same document. Each edit is written
 * into all of those tab records and pushed onto the canvas of any pane currently *showing* one of
 * them; a mirrored tab sitting in the background is up to date the moment it is selected, without a
 * canvas of its own.
 *
 * **The push is coalesced.** Adopting a document on the other pane costs a full relayout + render
 * over every page, and edits arrive as fast as the pen moves, so [propagate] only records the latest
 * document and schedules one [flush] on the next main-thread pass. Everything that *reads* a tab
 * record — snapshotting, persisting — flushes first, so the deferral is never observable.
 */
class MirrorSync(
    private val panes: List<EditorPane>,
    /** How a coalesced flush is scheduled; injectable so tests can run it inline. */
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {

    /** The newest edit waiting to be fanned out, and the pane it was made in. */
    private var pendingSource: EditorPane? = null
    private var pendingDoc: Document? = null
    private var scheduled = false

    /**
     * Note [doc] — the result of an edit made in [source] — as the state the other views of that
     * document should catch up to. A no-op when nothing else is looking at it, which is the ordinary
     * single-view case.
     */
    fun propagate(source: EditorPane, doc: Document) {
        val active = source.tabs.active ?: return
        val key = active.docKey
        if (panes.none { p -> p.tabs.tabs.any { it.docKey == key && it.id != active.id } }) return
        pendingSource = source
        pendingDoc = doc
        if (scheduled) return
        scheduled = true
        post { flush() }
    }

    /**
     * Fan the latest pending edit out now: into every tab record holding that document key, and onto
     * the canvas of any other pane showing one. Called on the scheduled pass, and directly by anyone
     * about to read the tab records. Cheap and safe when nothing is pending.
     */
    fun flush() {
        scheduled = false
        val source = pendingSource
        val doc = pendingDoc
        pendingSource = null
        pendingDoc = null
        if (source == null || doc == null) return
        val key = source.tabs.active?.docKey ?: return
        for (p in panes) {
            // The record now holds the real document, so it counts as hydrated even if it was still a
            // placeholder from the session index — the edit must not be lost to a later snapshot read.
            // Every record takes the *same* immutable document instance, so this shares one graph
            // rather than copying it per tab.
            p.tabs.updateMatching({ it.docKey == key }) { it.copy(document = doc, hydrated = true) }
            if (p === source) continue
            if (p.tabs.active?.docKey == key) p.surface?.applyMirroredDocument(doc)
        }
    }
}
