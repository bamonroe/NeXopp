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

    /**
     * Every element on [page] whose bounds lie wholly inside [rect] (desktop rectangle-select).
     *
     * The region is grown by [TAP_PAD] first, for the same reason [pickTopmost] pads: a hairline
     * stroke or an empty text box has a box of (near) zero extent, and a user who drags the
     * rectangle right along it has clearly enclosed it even if it lands a fraction outside.
     */
    fun inRect(page: Page, rect: Bounds): Set<ElementRef> {
        val padded = rect.expand(TAP_PAD)
        val hits = LinkedHashSet<ElementRef>()
        page.layers.forEachIndexed { li, layer ->
            layer.elements.forEachIndexed { ei, el ->
                if (ElementBounds.isHitTestable(el) && ElementBounds.of(el).containedBy(padded)) {
                    hits += ElementRef(li, ei)
                }
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
     *
     * Containment is tolerant by [TAP_PAD]: a point counts as inside when it is strictly inside the
     * polygon *or* within [TAP_PAD] pt of one of its edges. This matches the padding [pickTopmost]
     * and [inRect] apply, so a thin stroke traced closely by the lasso isn't dropped for landing a
     * hair outside the traced line.
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
    private fun enclosedBy(poly: List<Vec2>, el: Element): Boolean = when {
        // No trustworthy geometry (unmodelled element, empty stroke) — never enclosed.
        !ElementBounds.isHitTestable(el) -> false
        el is Stroke -> el.points.all { contains(poly, it.x, it.y) }
        else -> {
            val b = ElementBounds.of(el)
            contains(poly, b.left, b.top) && contains(poly, b.right, b.top) &&
                contains(poly, b.right, b.bottom) && contains(poly, b.left, b.bottom)
        }
    }

    /**
     * True when (x, y) is inside [poly] or within [TAP_PAD] of its boundary — the tolerant
     * containment rule shared by every lasso test.
     */
    private fun contains(poly: List<Vec2>, x: Double, y: Double): Boolean =
        strictlyInside(poly, x, y) || nearEdge(poly, x, y, TAP_PAD)

    /** Even-odd ray-cast point-in-polygon test (pt space). */
    private fun strictlyInside(poly: List<Vec2>, x: Double, y: Double): Boolean {
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

    /** True when (x, y) lies within [pad] pt of any edge of [poly]. */
    private fun nearEdge(poly: List<Vec2>, x: Double, y: Double, pad: Double): Boolean {
        var j = poly.size - 1
        for (i in poly.indices) {
            if (distToSegment(x, y, poly[j], poly[i]) <= pad) return true
            j = i
        }
        return false
    }

    /** Distance from (x, y) to the segment a–b (pt space). */
    private fun distToSegment(x: Double, y: Double, a: Vec2, b: Vec2): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        val px = a.x + t * dx
        val py = a.y + t * dy
        return kotlin.math.hypot(x - px, y - py)
    }

    /** The topmost (last-drawn) element whose padded bounds contain (x, y), or null. */
    fun pickTopmost(page: Page, x: Double, y: Double): ElementRef? {
        for (li in page.layers.indices.reversed()) {
            val elements = page.layers[li].elements
            for (ei in elements.indices.reversed()) {
                val el = elements[ei]
                if (ElementBounds.isHitTestable(el) && ElementBounds.of(el).expand(TAP_PAD).contains(x, y)) {
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
