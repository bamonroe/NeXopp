package com.xopp.android.panes

import com.xopp.android.format.model.Document
import com.xopp.android.tabs.OpenTab

/**
 * Keeps the several views of one document in step.
 *
 * The same document may be open in both split panes (long-press a tab → "Mirror on other view"), and
 * the two are *views*, not copies: each pane keeps its own scroll, zoom and page, but there is one
 * document behind them, so a stroke drawn on one side appears on the other at once.
 *
 * Tabs say which views belong together through [OpenTab.docKey] — mirroring carries the key over, so
 * every tab holding that key, in either pane, is a view of the same document. On each edit this
 * writes the new document into all of those tab records and pushes it onto the canvas of any pane
 * currently *showing* one of them; a mirrored tab sitting in the background is up to date the moment
 * it is selected, without a canvas of its own.
 */
class MirrorSync(private val panes: List<EditorPane>) {

    /**
     * Fan [doc] — the result of an edit made in [source] — out to the other views of that document.
     * A no-op when nothing else is looking at it, which is the ordinary single-view case.
     */
    fun propagate(source: EditorPane, doc: Document) {
        val active = source.tabs.active ?: return
        val key = active.docKey
        if (panes.none { p -> p.tabs.tabs.any { it.docKey == key && it.id != active.id } }) return
        for (p in panes) {
            // The record now holds the real document, so it counts as hydrated even if it was still a
            // placeholder from the session index — the edit must not be lost to a later snapshot read.
            p.tabs.updateMatching({ it.docKey == key }) { it.copy(document = doc, hydrated = true) }
            if (p === source) continue
            if (p.tabs.active?.docKey == key) p.surface?.applyMirroredDocument(doc)
        }
    }
}
