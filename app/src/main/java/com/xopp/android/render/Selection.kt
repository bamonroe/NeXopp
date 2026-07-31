package com.xopp.android.render

import com.xopp.android.format.model.Page

/**
 * A stable address for one element on a page: its layer index and its index within that layer.
 * The selection tool addresses elements by position (not identity) because a move rewrites the
 * element objects but never reorders them, so these indices stay valid across a drag.
 */
data class ElementRef(val layerIndex: Int, val elementIndex: Int)

/**
 * Pure queries that turn a page + a gesture into a set of [ElementRef]s: rectangle-select
 * containment and single-tap topmost pick, plus the combined bounds of a selection. Free of
 * Android types so it's unit-testable on the JVM; [DrawingSurfaceView] converts touches to
 * page-local pt and calls in here.
 */
object SelectionTester {

    /** Extra pt margin around an element's bounds when tap-testing, so thin strokes are pickable. */
    private const val TAP_PAD = 4.0

    /** Every element on [page] whose bounds lie wholly inside [rect] (desktop rectangle-select). */
    fun inRect(page: Page, rect: Bounds): Set<ElementRef> {
        val hits = LinkedHashSet<ElementRef>()
        page.layers.forEachIndexed { li, layer ->
            layer.elements.forEachIndexed { ei, el ->
                if (ElementBounds.of(el).containedBy(rect)) hits += ElementRef(li, ei)
            }
        }
        return hits
    }

    /** The topmost (last-drawn) element whose padded bounds contain (x, y), or null. */
    fun pickTopmost(page: Page, x: Double, y: Double): ElementRef? {
        for (li in page.layers.indices.reversed()) {
            val elements = page.layers[li].elements
            for (ei in elements.indices.reversed()) {
                if (ElementBounds.of(elements[ei]).expand(TAP_PAD).contains(x, y)) {
                    return ElementRef(li, ei)
                }
            }
        }
        return null
    }

    /** The union of the bounds of every element in [refs] on [page], or null if the set is empty. */
    fun boundsOf(page: Page, refs: Set<ElementRef>): Bounds? {
        var acc: Bounds? = null
        for (ref in refs) {
            val el = page.layers.getOrNull(ref.layerIndex)?.elements?.getOrNull(ref.elementIndex) ?: continue
            val b = ElementBounds.of(el)
            acc = acc?.union(b) ?: b
        }
        return acc
    }
}
