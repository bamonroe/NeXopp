package com.xopp.android.tabs

/**
 * The open-tab list and which tab is active — the ordering/selection rules, with no Android or
 * canvas dependency so they can be unit-tested directly.
 *
 * The manager never touches documents or files: the host (`MainActivity`) snapshots the canvas into
 * the outgoing tab before [select] and loads the incoming one after, and [TabStore] persists.
 */
class TabManager(initial: List<OpenTab> = emptyList(), activeIndex: Int = 0) {

    private val items = ArrayList(initial)

    /** Index of the showing tab; always in range while [tabs] is non-empty. */
    var activeIndex: Int = activeIndex.coerceIn(0, maxOf(0, initial.size - 1))
        private set

    val tabs: List<OpenTab> get() = items

    val active: OpenTab? get() = items.getOrNull(activeIndex)

    val isEmpty: Boolean get() = items.isEmpty()

    /** Append [tab] and make it the showing one — what opening a file or a new document does. */
    fun open(tab: OpenTab): Int {
        items.add(tab)
        activeIndex = items.lastIndex
        return activeIndex
    }

    /** Swap the showing tab's record (a save renaming it, a snapshot, an adopted PDF). */
    fun replaceActive(tab: OpenTab) {
        if (items.isEmpty()) items.add(tab) else items[activeIndex] = tab
    }

    /** Rewrite the showing tab in place; no-op when nothing is open. */
    fun updateActive(edit: (OpenTab) -> OpenTab) {
        val current = active ?: return
        items[activeIndex] = edit(current)
    }

    /**
     * Close the tab at [index]. Selection follows the neighbour on the left when the closed tab was
     * at or before the active one, so closing the tab you are looking at leaves you on its
     * predecessor rather than jumping to the end. Returns the removed tab, or null for a bad index.
     */
    fun close(index: Int): OpenTab? {
        if (index !in items.indices) return null
        val removed = items.removeAt(index)
        activeIndex = when {
            items.isEmpty() -> 0
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> (index - 1).coerceAtLeast(0)
            else -> activeIndex
        }.coerceAtMost(items.lastIndex)
        return removed
    }

    /** Show the tab at [index]; ignores an out-of-range index. Returns true when the showing tab changed. */
    fun select(index: Int): Boolean {
        if (index !in items.indices || index == activeIndex) return false
        activeIndex = index
        return true
    }

    /** The session to hand to [TabStore] — the list plus the selection, exactly as it stands. */
    fun session(): TabSession = TabSession(items.toList(), activeIndex)
}
