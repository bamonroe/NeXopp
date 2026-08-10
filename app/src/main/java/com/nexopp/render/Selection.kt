package com.nexopp.render

import com.nexopp.format.model.Element
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke

/**
 * A stable address for one element on a page: its layer index and its index within that layer.
 * The selection tool addresses elements by position (not identity) because a move rewrites the
 * element objects but never reorders them, so these indices stay valid across a drag.
 */
data class ElementRef(val layerIndex: Int, val elementIndex: Int)

/** A point in page-local pt space — the vertices of a lasso polygon. */
data class Vec2(val x: Double, val y: Double)

/**
 * Pure queries that turn a page + a gesture into a set of [ElementRef]s: rectangle-select
 * containment and single-tap topmost pick, plus the combined bounds of a selection. Free of
 * Android types so it's unit-testable on the JVM; [DrawingSurfaceView] converts touches to
 * page-local pt and calls in here.
 */
object SelectionTester {

    /** Extra pt margin around an element's bounds when tap-testing — owned by [ElementBounds]. */
    private const val TAP_PAD = ElementBounds.TAP_PAD

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

    /**
     * Every element on [page] that lies wholly inside the lasso [polygon] (page-local pt) — the
     * free-form analogue of [inRect]. A stroke is tested against its own points rather than its
     * bounding box: a diagonal or curved stroke drawn inside the lasso has box corners *outside*
     * it, so the box rule would silently drop exactly the strokes the user traced around. Elements
     * that really are rectangles (images, TeX, text) still use their four box corners. A degenerate
     * polygon (< 3 points) selects nothing.
     */
    fun inPolygon(page: Page, polygon: List<Vec2>): Set<ElementRef> {
        if (polygon.size < 3) return emptySet()
        val hits = LinkedHashSet<ElementRef>()
        page.layers.forEachIndexed { li, layer ->
            layer.elements.forEachIndexed { ei, el ->
                if (enclosedBy(polygon, el)) hits += ElementRef(li, ei)
            }
        }
        return hits
    }

    /** True when every part of [el] we can test lies inside [poly]. */
    private fun enclosedBy(poly: List<Vec2>, el: Element): Boolean = when (el) {
        // An empty stroke has no geometry to enclose, and would otherwise pass vacuously.
        is Stroke -> el.points.isNotEmpty() && el.points.all { contains(poly, it.x, it.y) }
        else -> {
            val b = ElementBounds.of(el)
            contains(poly, b.left, b.top) && contains(poly, b.right, b.top) &&
                contains(poly, b.right, b.bottom) && contains(poly, b.left, b.bottom)
        }
    }

    /** Even-odd ray-cast point-in-polygon test (pt space). */
    private fun contains(poly: List<Vec2>, x: Double, y: Double): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y)) {
                val xCross = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
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
