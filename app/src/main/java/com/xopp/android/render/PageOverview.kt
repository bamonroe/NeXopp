package com.xopp.android.render

import com.xopp.android.format.model.Page

/**
 * The page-overview grid's view state: whether the grid is in edit mode, which pages are picked,
 * what is on the page clipboard, and the in-flight drag-to-reorder lift.
 *
 * None of it is ever written to the `.xopp` — the document only changes when a delete, paste or drop
 * commits through [DrawingSurfaceView]'s undoable page edits. Holding it here keeps a dozen loose
 * fields (and the rules tying them together, such as "leaving edit mode abandons the lift") out of
 * the view.
 *
 * In view mode — the default — the grid is purely a way to look at and navigate the document: a tap
 * jumps to a page, with no selection or reordering. Edit mode turns on tap-to-select,
 * drag-to-reorder and the copy/delete tooling.
 */
internal class PageOverview(
    /** Notified with how many pages are selected whenever the selection changes. */
    private val onSelectionChanged: (Int) -> Unit,
    /** Notified with how many pages are on the clipboard whenever a copy changes it. */
    private val onClipboardChanged: (Int) -> Unit,
    /** Called when the state changed in a way the canvas has to repaint for. */
    private val invalidate: () -> Unit,
) {

    var editMode = false
        private set

    /** Pages picked in the grid (by index) — the set a bulk delete acts on. */
    val selected: Set<Int> get() = picked
    private val picked = mutableSetOf<Int>()

    /** Pages copied out of the grid, awaiting a paste. Pages are immutable, so this is just a list. */
    var clipboard: List<Page> = emptyList()
        private set

    /** The page being dragged, or -1 when no reorder drag is in flight. */
    var dragIndex = -1
        private set
    /** Where the lifted page would land on release, or -1. */
    var dropIndex = -1
        private set
    /** True between touch-down and the long-press firing (or the touch disqualifying itself). */
    var armed = false
        private set
    var armDownX = 0f
        private set
    var armDownY = 0f
        private set

    val dragging: Boolean get() = dragIndex >= 0

    /**
     * Turn grid editing on or off. Returns true when the caller must also abandon any lift in
     * flight — leaving edit mode drops the selection here, so coming back to the grid to read never
     * has stale selection chrome on it.
     */
    fun setEditMode(on: Boolean): Boolean {
        if (on == editMode) return false
        editMode = on
        if (!on) clearSelection()
        invalidate()
        return !on
    }

    /** Drop the selection (e.g. on leaving the grid, or after a delete). */
    fun clearSelection() {
        if (picked.isEmpty()) return
        picked.clear()
        onSelectionChanged(0)
        invalidate()
    }

    /** Add or remove page [index] from the selection; [pageCount] bounds the valid indices. */
    fun toggleSelection(index: Int, pageCount: Int) {
        if (index < 0 || index >= pageCount) return
        if (!picked.remove(index)) picked.add(index)
        onSelectionChanged(picked.size)
        invalidate()
    }

    /** Put [pages] on the clipboard. The selection is left alone, so "copy, then paste after these"
     *  reads naturally. No-op for an empty copy, so the clipboard survives a stray Copy. */
    fun copyToClipboard(pages: List<Page>) {
        if (pages.isEmpty()) return
        clipboard = pages
        onClipboardChanged(pages.size)
    }

    /** Arm the long-press that lifts a page, remembering where the finger went down. */
    fun arm(x: Float, y: Float) {
        armed = true
        armDownX = x
        armDownY = y
    }

    /** The long-press fired: [index] is lifted and is its own initial drop slot. */
    fun lift(index: Int) {
        armed = false
        dragIndex = index
        dropIndex = index
    }

    /** Disarm a pending long-press without lifting (the touch turned into a pan). */
    fun disarm() { armed = false }

    /** Track the finger onto slot [index]; false when it hasn't changed and no repaint is due. */
    fun moveDropTo(index: Int): Boolean {
        if (index == dropIndex) return false
        dropIndex = index
        return true
    }

    /** End any lift, returning the (from, to) reorder it commits — or null when there is nothing to do. */
    fun endDrag(): Pair<Int, Int>? {
        val from = dragIndex
        val to = dropIndex
        dragIndex = -1
        dropIndex = -1
        return if (from < 0 || to < 0 || to == from) null else from to to
    }
}
