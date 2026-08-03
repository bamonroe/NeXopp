package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

/**
 * Pure page-list edits for the editor: insert and delete pages. Kept Android-free so the
 * document-structure logic is unit-testable off-device (see `docs/architecture.md`). A new page
 * inherits the **size** and the **paper ruling** of the page it follows, with a single empty layer —
 * but never its content: a `pdf`/`pixmap` background is dropped to a plain white sheet so the fresh
 * page can't re-show the source page's PDF/image underneath (which read as a duplicate).
 */
object PageOps {

    /** A plain white blank page's fill colour (ARGB). */
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** A copy of [pages] with a fresh blank page inserted after [index]. Empty input is returned as-is. */
    fun addAfter(pages: List<Page>, index: Int): List<Page> {
        if (pages.isEmpty()) return pages
        val i = index.coerceIn(0, pages.lastIndex)
        val src = pages[i]
        val fresh = src.copy(background = blankBackground(src.background), layers = listOf(Layer(emptyList())))
        return pages.toMutableList().apply { add(i + 1, fresh) }
    }

    /**
     * The background for a freshly-inserted blank page. A [Background.Solid] carries only paper ruling
     * (no content), so it's kept as-is for continuity; a `pdf`/`pixmap` background would re-show the
     * source page's content, so it's replaced with a plain white sheet.
     */
    private fun blankBackground(source: Background): Background = when (source) {
        is Background.Solid -> source
        else -> Background.Solid(WHITE, "plain")
    }

    /**
     * [pages] with [added] appended after them — the "Append" half of PDF import. A brand-new
     * document is a single empty sheet nobody has drawn on; keeping it would leave a stray blank page
     * in front of the imported PDF, so that one case is dropped and the appended pages stand alone.
     */
    fun appendPages(pages: List<Page>, added: List<Page>): List<Page> {
        if (added.isEmpty()) return pages
        if (pages.size == 1 && isPristineBlank(pages[0])) return added
        return pages + added
    }

    /** True for an untouched blank sheet: a solid background and not a single element on any layer. */
    private fun isPristineBlank(page: Page): Boolean =
        page.background is Background.Solid && page.layers.all { it.elements.isEmpty() }

    /** A copy of [pages] with page [index] removed. The last remaining page is never removed. */
    fun removeAt(pages: List<Page>, index: Int): List<Page> {
        if (pages.size <= 1) return pages
        val i = index.coerceIn(0, pages.lastIndex)
        return pages.toMutableList().apply { removeAt(i) }
    }
}
