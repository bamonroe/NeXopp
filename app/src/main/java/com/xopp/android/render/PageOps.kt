package com.xopp.android.render

import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

/**
 * Pure page-list edits for the editor: insert and delete pages. Kept Android-free so the
 * document-structure logic is unit-testable off-device (see `docs/architecture.md`). A new page
 * inherits the size and background of the page it follows (matching desktop Xournal++), with a
 * single empty layer.
 */
object PageOps {

    /** A copy of [pages] with a fresh blank page inserted after [index]. Empty input is returned as-is. */
    fun addAfter(pages: List<Page>, index: Int): List<Page> {
        if (pages.isEmpty()) return pages
        val i = index.coerceIn(0, pages.lastIndex)
        val fresh = pages[i].copy(layers = listOf(Layer(emptyList())))
        return pages.toMutableList().apply { add(i + 1, fresh) }
    }

    /** A copy of [pages] with page [index] removed. The last remaining page is never removed. */
    fun removeAt(pages: List<Page>, index: Int): List<Page> {
        if (pages.size <= 1) return pages
        val i = index.coerceIn(0, pages.lastIndex)
        return pages.toMutableList().apply { removeAt(i) }
    }
}
