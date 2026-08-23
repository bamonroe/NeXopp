package com.nexopp

import android.graphics.Bitmap
import android.net.Uri
import com.nexopp.panes.EditorPane
import com.nexopp.render.PageThumbnail
import com.nexopp.render.PdfPageCache
import com.nexopp.render.blankDocument
import com.nexopp.tabs.DocColors
import com.nexopp.tabs.OpenTab
import com.nexopp.tabs.TabStore
import com.nexopp.ui.TabsUiState
import java.io.File

// --- tabs: several documents open at once, restored on the next launch ----------------------
//
// [MainActivity]'s tab/session half, kept out of the activity file itself.
//
// A pane has exactly one canvas, so a tab switch is a swap: snapshot the outgoing document out of
// the surface ([snapshotActiveTab]), then load the incoming one into it ([showTab]). Everything a
// tab needs to come back — its document, source URI, save format, background PDF and page — lives
// in its [OpenTab] record, which is also what [TabStore] writes to disk.
//
// Every function here takes the [EditorPane] it applies to, defaulting to the pane in focus, so
// split view is just "the same thing, twice" — each pane keeps its own session and its own file.
//
// Undo history belongs to the surface, not the document, so it does not follow a tab switch: the
// incoming tab starts with a clean history. Its content, including unsaved edits, is intact.

/** A pane's tab strip view of its session, re-read whenever [MainActivity.tabsTick] changes. */
internal fun MainActivity.tabsUiState(p: EditorPane): TabsUiState {
    tabsTick.value // read so Compose re-invokes this when the session changes
    val dots = DocColors.assign(panes.flatMap { pane -> pane.tabs.tabs.map(OpenTab::docKey) })
    return TabsUiState(
        titles = p.tabs.tabs.map { it.title },
        dotColors = p.tabs.tabs.map { dots[it.docKey] },
        activeIndex = p.tabs.activeIndex,
        onSelect = { selectTab(it, p) },
        onClose = { closeTab(it, p) },
        onNew = { newTab(p) },
        onMove = { sendTabToOtherPane(it, p, keepHere = false) },
        onMirror = { sendTabToOtherPane(it, p, keepHere = true) },
        onReorder = { from, to -> reorderTab(from, to, p) },
        // The showing tab's live edits are only in the canvas until it is snapshotted, so the
        // overview would otherwise preview a stale page for the tab you are looking at.
        onOverview = { snapshotActiveTab(p) },
        preview = { index, widthPx, onReady -> previewTabPage(p, index, widthPx, onReady) },
    )
}

/**
 * Hand [from]'s tab at [index] to the other pane — the long-press actions on a tab.
 *
 * With [keepHere] false this is a *move*: the tab leaves this pane (which falls back to a blank
 * document if it was the last one) and opens in the other. With [keepHere] true it is a *mirror*:
 * a second **view** of the same document opens in the other pane. The two views share one
 * document — the new tab keeps the original's [OpenTab.docKey], so `MirrorSync` pushes every edit
 * from either side straight into the other — while keeping their own scroll, zoom and page, and
 * both write back to the same file. Split view is opened if it was closed, since otherwise there
 * is nowhere to put it.
 */
internal fun MainActivity.sendTabToOtherPane(index: Int, from: EditorPane, keepHere: Boolean) {
    val other = panes.firstOrNull { it !== from } ?: return
    snapshotActiveTab(from)
    val source = from.tabs.tabs.getOrNull(index) ?: return
    hydrate(other)
    val newId = TabStore.newId()
    // A tab still waiting to be parsed is handed over as a *file*: its snapshot is copied under
    // the copy's new id, so the other pane can hydrate it itself rather than us parsing a whole
    // document inside this long-press.
    if (!source.hydrated) other.store.adopt(from.store, source.id, newId)
    other.tabs.open(source.copy(id = newId))
    if (!keepHere) {
        val showing = from.tabs.active?.id
        from.tabs.close(index)
        if (from.tabs.isEmpty) from.tabs.open(blankTab())
        from.tabs.active?.takeIf { it.id != showing }?.let { show(it, from) }
    }
    // Only paints now if that pane already has a canvas; otherwise [restoreTabs] shows it when
    // split view builds one.
    other.tabs.active?.let { show(it, other) }
    splitView.value = true
    from.persist()
    other.persist()
    tabsTick.value++
}

/** Load a pane's stored session into its manager without touching a canvas, if it has none yet. */
internal fun MainActivity.hydrate(p: EditorPane) {
    if (!p.tabs.isEmpty) return
    val session = p.store.load() ?: return
    session.tabs.forEach(p.tabs::open)
    p.tabs.select(session.activeIndex)
}

/** Copy the live canvas back into the showing tab's record, so switching away doesn't lose it. */
internal fun MainActivity.snapshotActiveTab(p: EditorPane = pane) {
    // A mirrored edit may still be waiting on its coalesced pass; apply it first so the records this
    // is about to read (and the other pane's canvas) are current.
    mirrors.flush()
    val view = p.surface ?: return
    // A tab still being parsed in the background hasn't reached the canvas yet, so what is on it
    // belongs to the tab before it — copying that in would overwrite the pending document.
    if (p.tabs.active?.hydrated == false) return
    // Likewise for a tab whose snapshot we failed to read: the canvas shows a blank placeholder, and
    // copying it into the record would hand the next persist() a blank to write over the tab's file.
    if (p.tabs.active?.loadFailed == true) return
    p.tabs.updateActive {
        it.copy(
            document = view.toDocument(),
            format = p.saveFormat,
            pdfPath = view.pdfSourceFile()?.absolutePath,
            page = view.visiblePageIndex(),
        )
    }
}

/**
 * Put [p]'s showing tab on its canvas, parsing its snapshot first if it is still a lazy
 * placeholder from the session index ([TabStore.load]).
 *
 * The parse is gzip + XML over a whole document, far too slow for the main thread, so an
 * unhydrated tab is read on a worker and handed to [showTab] when it lands — the canvas keeps
 * whatever it was showing for those few frames instead of freezing. A tab whose selection has
 * moved on by then is dropped: only the still-showing tab is painted.
 */
internal fun MainActivity.show(tab: OpenTab, p: EditorPane = pane) {
    if (tab.hydrated) return showTab(tab, p)
    Thread {
        val full = p.store.hydrate(tab)
        runOnUiThread {
            if (p.tabs.active?.id != full.id) return@runOnUiThread
            p.tabs.updateActive { if (it.id == full.id) full else it }
            showTab(full, p)
            // Say so rather than letting a blank page pass for the restored document — the bytes are
            // still on disk as `<id>.xopp.corrupt` and nothing will overwrite them.
            if (full.loadFailed) toast("Couldn't restore \"${full.title}\" — its saved copy is kept for recovery")
            tabsTick.value++
        }
    }.start()
}

/** Load [tab] onto [p]'s canvas: its document, background PDF, save format and page. */
internal fun MainActivity.showTab(tab: OpenTab, p: EditorPane = pane) {
    val view = p.surface ?: return
    p.saveFormat = tab.format
    p.pendingSaveName = tab.title
    val pdf = tab.pdfPath?.let(::File)?.takeIf(File::exists)
    // Shared, so a document mirrored into both panes rasterises from one renderer, not two.
    view.setPdfSource(pdf?.let(PdfPageCache::shared))
    view.setPdfTextIndex(null) // cleared until extraction below finishes
    view.load(tab.document)
    // A different document is on the canvas now: the outgoing tab's dirty state and interval
    // baseline don't describe it, and inheriting them would autosave this one for someone else's
    // edits. (Only for the focused pane — the timer follows whichever document the menus act on.)
    if (p === pane) autoSave.reset()
    if (tab.page > 0) view.goToPage(tab.page)
    if (pdf != null) extractPdfTextInBackground(pdf, view)
}

/**
 * Rasterise the page tab [index] of [p] is showing, for the tab overview grid, and hand it back on
 * the main thread.
 *
 * Both halves run off the main thread: a tab still waiting to be parsed ([OpenTab.hydrated] false)
 * costs a whole gzip + XML read, and even a parsed one can be a document of many thousand strokes,
 * neither of which belongs in the tap that opened the grid. Hydrating here deliberately does *not*
 * write the parsed tab back into the session — looking at the overview shouldn't undo the lazy
 * restore that keeps a cold start off the ANR watchdog.
 *
 * The whole grid's previews share **one** worker ([MainActivity.previewWorker]) so only a single
 * document is ever parsed at a time. Fanning a thread out per tab instead put every open document
 * in memory at once, which is an out-of-memory kill on a session of large files.
 */
internal fun MainActivity.previewTabPage(
    p: EditorPane,
    index: Int,
    widthPx: Int,
    onReady: (Bitmap?) -> Unit,
) {
    val tab = p.tabs.tabs.getOrNull(index) ?: return onReady(null)
    previewWorker.execute {
        val full = if (tab.hydrated) tab else runCatching { p.store.hydrate(tab) }.getOrNull()
        val page = full?.document?.pages?.getOrNull(full.page)
        val bitmap = page?.let { runCatching { PageThumbnail.render(it, widthPx) }.getOrNull() }
        runOnUiThread { onReady(bitmap) }
    }
}

/** Switch [p] to the tab at [index], snapshotting the one being left. */
internal fun MainActivity.selectTab(index: Int, p: EditorPane = pane) {
    snapshotActiveTab(p)
    if (!p.tabs.select(index)) return
    p.tabs.active?.let { show(it, p) }
    tabsTick.value++
    p.persist()
}

/**
 * Reorder [p]'s strip: the tab at [from] moves to [to] — the drag gesture on a tab. No document
 * is loaded or unloaded, so the canvas is left alone; only the strip order and the saved session
 * change.
 */
internal fun MainActivity.reorderTab(from: Int, to: Int, p: EditorPane = pane) {
    if (!p.tabs.move(from, to)) return
    tabsTick.value++
    p.persist()
}

/**
 * Close [p]'s tab at [index]. Closing the last one leaves a fresh blank document rather than an
 * empty pane, and the canvas is only reloaded when the *showing* tab actually changed.
 */
internal fun MainActivity.closeTab(index: Int, p: EditorPane = pane) {
    snapshotActiveTab(p)
    val showing = p.tabs.active?.id
    p.tabs.close(index) ?: return
    if (p.tabs.isEmpty) p.tabs.open(blankTab())
    p.tabs.active?.takeIf { it.id != showing }?.let { show(it, p) }
    tabsTick.value++
    p.persist()
    prunePdfCache() // the closed tab's background PDF is now unreferenced
}

/** Open a fresh blank document in a new tab of [p] and switch to it. */
internal fun MainActivity.newTab(p: EditorPane = pane) {
    snapshotActiveTab(p)
    p.tabs.open(blankTab())
    p.tabs.active?.let { show(it, p) }
    tabsTick.value++
    p.persist()
}

internal fun blankTab() =
    OpenTab(TabStore.newId(), MainActivity.UNTITLED, blankDocument())

/**
 * Bring back the tabs [p] was last closed with, or start it on a single blank one. Called once
 * that pane's canvas exists; guarded so a surface rebuilt within this activity doesn't restore
 * twice (and so the right-hand pane only restores when split view actually opens it).
 */
internal fun MainActivity.restoreTabs(p: EditorPane, then: () -> Unit = {}) {
    // A pane that already has a session is being handed a *replacement* canvas (split view was
    // switched off and on again): put its showing document straight back onto the new surface.
    if (!p.tabs.isEmpty) {
        p.tabs.active?.let { show(it, p) }
        then()
        return
    }
    // Reading the index and parsing the showing tab's snapshot is disk + gzip + XML work, so it
    // happens on a worker; only the (cheap) hand-off to the canvas runs on the main thread. Doing
    // it inline is what pushed the first frame past the ANR watchdog on a big restored session.
    Thread {
        val session = p.store.load()
        runOnUiThread {
            if (p.tabs.isEmpty) {
                if (session == null) {
                    p.tabs.open(blankTab())
                } else {
                    session.tabs.forEach(p.tabs::open)
                    p.tabs.select(session.activeIndex)
                }
                p.tabs.active?.let { show(it, p) }
                tabsTick.value++
            }
            then()
        }
    }.start()
}

/** Write the focused pane's session out, on every tab change and on the way to the background. */
internal fun MainActivity.persistTabs() {
    mirrors.flush() // don't write a session that predates a pending mirrored edit
    pane.persist()
    prunePdfCache()
}

/**
 * Drop the background PDFs no open tab refers to any more. Each open allocates its own file
 * (`PdfStore`), so without this sweep the folders would grow with every document opened.
 *
 * The live surfaces are counted alongside the tab records: a document whose PDF was just
 * imported has not been snapshotted back into its tab yet, and deleting the file out from under
 * the canvas is precisely the bug this store exists to prevent.
 */
internal fun MainActivity.prunePdfCache() {
    val live = panes.flatMap { p ->
        p.tabs.tabs.map(OpenTab::pdfPath) + listOf(p.surface?.pdfSourceFile()?.absolutePath)
    }
    // Pixmap copies aren't recorded in a tab (a reopened document re-resolves them), so only the
    // pictures the live canvases are decoding from are kept.
    val liveImages = panes.flatMap { p ->
        p.surface?.imageSources()?.values?.map(File::getAbsolutePath).orEmpty()
    }
    io.prune(live, liveImages)
}

/**
 * Turn split view on or off. Both directions snapshot every pane first: a canvas rebuilt by the
 * toggle reloads its tab's stored document, so an edit that only exists on the live surface — an
 * undo, say — would otherwise be lost and the pre-edit page would come back. Persisting at the
 * same moment also keeps the on-disk session current across the toggle. Leaving split view then
 * hands focus back to the left pane, so a menu action never targets a canvas that is no longer on
 * screen. The pane's tabs are kept (and stay on disk), so turning split view back on brings the
 * same documents back.
 */
internal fun MainActivity.toggleSplitView() {
    val on = !splitView.value
    panes.forEach { snapshotActiveTab(it); it.persist() }
    if (!on) activePane.value = 0
    splitView.value = on
}

/** The file name behind a `content://` URI, for the tab's label. Falls back to `UNTITLED`. */
internal fun MainActivity.displayName(uri: Uri): String = io.displayName(uri, MainActivity.UNTITLED)
