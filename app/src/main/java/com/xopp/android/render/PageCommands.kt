package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Page

/**
 * The document-editing command surface: every page-level and layer-level edit the chrome can ask
 * for, expressed as one undoable change to the [Document].
 *
 * It is the single home of the two commit pipelines — [editPages] for a change to the page *list*
 * (add/remove/reorder/paste, which invalidates anything keyed by page index) and [editVisiblePage]
 * for a change *within* the page in view (paper style, size, layers). [DrawingSurfaceView] keeps
 * only the view state those pipelines have to poke (selection, hidden layers, the active layer) and
 * hands it in as callbacks, so the view is left routing events and drawing while the "what does this
 * command do to the document" half lives here, testable without a canvas.
 *
 * Nothing here touches [PageOps]/[LayerOps]' internals: those stay the pure list/page transforms and
 * this class is the facade that turns one into an undo step plus the view refresh it implies.
 */
internal class PageCommands(
    /** The working document, read fresh on every command (the view owns it). */
    private val document: () -> Document,
    /** Adopt [Document] as an edit: assign it, snapshot the previous one, notify the history. */
    private val commit: (Document) -> Unit,
    /** Index of the page under the viewport centre — what the per-page commands act on. */
    private val visiblePage: () -> Int,
    /** Index of the page nearest the viewport centre — where add/remove/paste land. */
    private val currentPage: () -> Int,
    /** The page-overview view state (selection and clipboard) the page commands read and clear. */
    private val overview: PageOverview,
    /** Drop the per-(page,layer) visibility overrides — page indices or layer indices just moved. */
    private val resetLayerVisibility: () -> Unit,
    /** Reset the active layer to "the top one" — same reason. */
    private val clearActiveLayer: () -> Unit,
    /** Re-fit the layout, re-clamp the viewport and repaint after a committed edit. */
    private val refresh: () -> Unit,
    /** Notified with the new page count whenever the page list changes. */
    private val onPageCountChanged: (Int) -> Unit,
    /** Notified after every command, so the layer panel re-reads its rows. */
    private val onLayersChanged: () -> Unit,
) {

    // --- pages ---------------------------------------------------------------------------------

    /** Insert a blank page after the page in view (see [PageOps.addAfter]: keeps size and solid ruling,
     *  drops a PDF/pixmap background to a plain sheet so it isn't a duplicate). */
    fun addPage() = editPages(PageOps.addAfter(document().pages, currentPage()))

    /**
     * Append [pages] after the document's existing pages as one undoable edit — the "Append" half of
     * PDF import (see [PageOps.appendPages]). Unlike a load this keeps the current annotations and
     * undo history, so an accidental import is one undo away.
     */
    fun appendPages(pages: List<Page>) = editPages(PageOps.appendPages(document().pages, pages))

    /**
     * Append [pages] *and* re-point the document's PDF background reference at [reference] in one
     * undoable edit — appending a second PDF, which merges into a single joined background PDF (see
     * [PdfMerger]). Both halves have to move together: the appended pages' `pageno` values index the
     * joined PDF, so a document holding the old reference with the new pages wouldn't round-trip.
     */
    fun appendPdfPages(pages: List<Page>, reference: String) {
        val retargeted = documentWithPdfReference(document(), reference, ABSOLUTE_DOMAIN)
        editPages(PageOps.appendPages(retargeted.pages, pages))
    }

    /** Delete the page currently in view. No-op when only one page remains. */
    fun removePage() = editPages(PageOps.removeAt(document().pages, currentPage()))

    /** Reorder page [from] to [to] as one undoable edit (the overview's drag-to-reorder drop). */
    fun movePage(from: Int, to: Int) = editPages(PageOps.move(document().pages, from, to))

    /**
     * Delete every selected page as one undoable edit and clear the selection. A selection covering
     * the whole document is refused by [PageOps.removeAll] (a document always keeps a page), so
     * nothing is deleted in that case.
     */
    fun deleteSelectedPages() {
        val pages = PageOps.removeAll(document().pages, overview.selected)
        overview.clearSelection()
        editPages(pages)
    }

    /**
     * Put the selected pages on the page clipboard, in document order. Nothing is edited yet — the
     * clipboard is view state that survives until the next copy (or the editor closing), so a copy can
     * be pasted repeatedly.
     */
    fun copySelectedPages() = overview.copyToClipboard(PageOps.copyOf(document().pages, overview.selected))

    /**
     * Paste the clipboard pages as one undoable edit, directly after the last selected page — or after
     * the page nearest the viewport centre when nothing is selected. The pasted pages carry their
     * strokes, layers, size and background verbatim (see [PageOps.copyOf]), so they round-trip.
     * Returns the index of the first pasted page, or null when the clipboard is empty.
     */
    fun pasteCopiedPages(): Int? {
        val copied = overview.clipboard
        if (copied.isEmpty()) return null
        val pages = document().pages
        val after = overview.selected.maxOrNull() ?: currentPage()
        val at = after.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        editPages(PageOps.insertAfter(pages, at, copied))
        return at + 1
    }

    /**
     * Set the visible page's paper [style] (plain/lined/ruled/graph/dotted) as one undoable edit.
     * No-op on PDF/pixmap pages, whose background isn't a solid sheet.
     */
    fun setPageBackgroundStyle(style: String) = editVisiblePage(resetViewState = false, op = { page ->
        val bg = page.background
        if (bg is Background.Solid && bg.style != style) page.copy(background = bg.copy(style = style)) else page
    })

    /**
     * Set the visible page's size to [widthPt] × [heightPt] points as one undoable edit; both are
     * clamped to a sane range. The stacked layout re-fits every page to the view width, so this
     * changes the page's on-screen aspect ratio (and the dimensions written to the `.xopp`).
     */
    fun setPageSize(widthPt: Double, heightPt: Double) = editVisiblePage(resetViewState = false, op = { page ->
        val w = widthPt.coerceIn(DrawingSurfaceView.PAGE_SIZE_MIN_PT, DrawingSurfaceView.PAGE_SIZE_MAX_PT)
        val h = heightPt.coerceIn(DrawingSurfaceView.PAGE_SIZE_MIN_PT, DrawingSurfaceView.PAGE_SIZE_MAX_PT)
        if (page.width == w && page.height == h) page else page.copy(width = w, height = h)
    })

    // --- layers --------------------------------------------------------------------------------

    /** Add a fresh empty layer above the top and make it active. */
    fun addLayer() = editVisiblePage(resetViewState = true, op = {
        val (p, _) = LayerOps.add(it); p
    }, after = { clearActiveLayer() /* "top layer" already resolves to the new one */ })

    /** Delete layer [index] (never the last remaining layer). */
    fun deleteLayer(index: Int) = editVisiblePage(resetViewState = true, op = { LayerOps.remove(it, index) })

    /** Merge layer [index] into the layer below it (never the bottom layer). */
    fun mergeLayerDown(index: Int) =
        editVisiblePage(resetViewState = true, op = { LayerOps.mergeDown(it, index) })

    /** Rename layer [index] ([name] blank clears the custom name). */
    fun renameLayer(index: Int, name: String) =
        editVisiblePage(resetViewState = false, op = { LayerOps.rename(it, index, name) })

    /** Reorder layer [from] to position [to] (changes z-order). */
    fun moveLayer(from: Int, to: Int) =
        editVisiblePage(resetViewState = true, op = { LayerOps.move(it, from, to) })

    // --- the two commit pipelines ----------------------------------------------------------------

    /** Apply a new page list as one undoable edit, if it actually differs. */
    fun editPages(pages: List<Page>) {
        val doc = document()
        if (pages === doc.pages) return
        commit(doc.copy(pages = pages))
        onPageCountChanged(pages.size)
        // Page indices shifted: view state keyed by page index is no longer valid.
        overview.clearSelection()
        resetLayerVisibility()
        clearActiveLayer()
        refresh()
        onLayersChanged()
    }

    /** Apply [op] to the visible page as one undoable edit; [after] runs post-commit, pre-refresh. */
    fun editVisiblePage(resetViewState: Boolean, op: (Page) -> Page, after: () -> Unit = {}) {
        val doc = document()
        val pi = visiblePage()
        val page = doc.pages.getOrNull(pi) ?: return
        val newPage = op(page)
        if (newPage === page) { after(); onLayersChanged(); return }
        if (resetViewState) resetLayerVisibility()
        commit(doc.copy(pages = doc.pages.toMutableList().also { it[pi] = newPage }))
        after()
        refresh()
        onLayersChanged()
    }
}
