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

    /**
     * A copy of [pages] with the page at [from] lifted out and re-inserted at [to] — the page-overview
     * drag-to-reorder edit. Indices are positions in the *result*, so moving page 0 to 2 in a 4-page
     * document yields `1,2,0,3`. An out-of-range [from] or a no-op move returns the list unchanged, so
     * callers can hand the result straight to an "only if it differs" edit.
     */
    fun move(pages: List<Page>, from: Int, to: Int): List<Page> {
        if (from !in pages.indices) return pages
        val target = to.coerceIn(0, pages.lastIndex)
        if (target == from) return pages
        return pages.toMutableList().apply { add(target, removeAt(from)) }
    }

    /**
     * The pages at [indices], in ascending document order — the page-overview copy. Out-of-range
     * indices are ignored, so an empty or fully out-of-range selection yields an empty list. Pages are
     * immutable, so the "copy" shares structure with the originals and is safe to re-insert as-is:
     * strokes, layers, page size, background and any PDF/image reference all come along verbatim and
     * are written out again by the `.xopp` writer, so the duplicate round-trips.
     */
    fun copyOf(pages: List<Page>, indices: Set<Int>): List<Page> =
        indices.filter { it in pages.indices }.sorted().map { pages[it] }

    /**
     * A copy of [pages] with [added] inserted directly *after* index [after]; a negative [after] puts
     * them in front of the document. [after] is clamped into range, and an empty [added] returns the
     * same instance so callers can hand the result straight to an "only if it differs" edit.
     */
    fun insertAfter(pages: List<Page>, after: Int, added: List<Page>): List<Page> {
        if (added.isEmpty()) return pages
        val at = (after + 1).coerceIn(0, pages.size)
        return pages.toMutableList().apply { addAll(at, added) }
    }

    /** A copy of [pages] with page [index] removed. The last remaining page is never removed. */
    fun removeAt(pages: List<Page>, index: Int): List<Page> {
        if (pages.size <= 1) return pages
        val i = index.coerceIn(0, pages.lastIndex)
        return pages.toMutableList().apply { removeAt(i) }
    }

    /**
     * A copy of [pages] with every page in [indices] removed — the page-overview multi-select delete.
     * Out-of-range indices are ignored. A document is never emptied: a selection covering *every* page
     * is refused outright (the list is returned unchanged) rather than silently sparing an arbitrary
     * page, and an empty or fully out-of-range selection is likewise a no-op returning the same
     * instance, so callers can hand the result straight to an "only if it differs" edit.
     */
    fun removeAll(pages: List<Page>, indices: Set<Int>): List<Page> {
        val drop = indices.filter { it in pages.indices }.toSet()
        if (drop.isEmpty() || drop.size >= pages.size) return pages
        return pages.filterIndexed { i, _ -> i !in drop }
    }
}
