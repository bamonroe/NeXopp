package com.xopp.android.render

import com.xopp.android.format.model.Element
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

/**
 * The vertical-space tool's pure edit: grab a horizontal line on a page and drag to insert (down) or
 * remove (up) vertical space, shifting everything below the line with the drag. Desktop Xournal++
 * has this as a core rail tool; it round-trips perfectly because it only rewrites element
 * coordinates, which every `.xopp` element already stores on disk.
 *
 * "Below the line" is decided by an element's **top** edge ([ElementBounds]): an element that starts
 * below the grab line moves whole, and one the line passes through stays put rather than being torn
 * in half. All layers of the page move together, matching desktop — the tool reflows the page, not
 * one layer of it.
 *
 * Free of Android types, so it is unit-testable on the JVM (see `VerticalSpaceOpsTest`).
 */
object VerticalSpaceOps {

    /**
     * How far a drag starting at [yPt] on page [pageIndex] may actually shift, given a requested
     * [dy] pt. Insertion (positive [dy]) is unbounded; removal (negative) stops once the topmost
     * moving element would cross above the grab line, so pulling up can close a gap but never
     * shuffles content past the line it was grabbed at.
     */
    fun clampShift(pages: List<Page>, pageIndex: Int, yPt: Double, dy: Double): Double {
        if (dy >= 0.0) return dy
        val page = pages.getOrNull(pageIndex) ?: return 0.0
        val highestTop = page.layers
            .flatMap { it.elements }
            .map { ElementBounds.of(it).top }
            .filter { it >= yPt }
            .minOrNull() ?: return 0.0 // nothing below the line: nothing to pull up
        return maxOf(dy, yPt - highestTop)
    }

    /**
     * [pages] with every element on page [pageIndex] whose top edge sits at or below [yPt] shifted
     * down by [dy] pt (negative pulls up). The shift is clamped by [clampShift]; a no-op returns the
     * same list so a live drag that can't move any further doesn't churn snapshots.
     */
    fun shiftBelow(pages: List<Page>, pageIndex: Int, yPt: Double, dy: Double): List<Page> {
        val page = pages.getOrNull(pageIndex) ?: return pages
        val shift = clampShift(pages, pageIndex, yPt, dy)
        // Nothing to move (no element below the line, or a clamped-to-zero pull) — return the same
        // list so a drag over empty space doesn't churn a snapshot and record an empty undo step.
        if (shift == 0.0 || page.layers.none { l -> l.elements.any { ElementBounds.of(it).top >= yPt } }) return pages
        val layers = page.layers.map { layer ->
            Layer(layer.elements.map { el -> shiftIfBelow(el, yPt, shift) }, layer.name)
        }
        return pages.toMutableList().also { it[pageIndex] = page.copy(layers = layers) }
    }

    private fun shiftIfBelow(element: Element, yPt: Double, dy: Double): Element =
        if (ElementBounds.of(element).top >= yPt) SelectionOps.translate(element, 0.0, dy) else element
}
