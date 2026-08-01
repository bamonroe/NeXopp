package com.xopp.android.render

import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure edits the selection tool applies to a page list: translate, resize (affine scale), rotate,
 * delete, recolour/re-width, and cut/copy/paste (move between pages) the elements named by a set of
 * [ElementRef]s. Every op returns a new page list (immutable pages/layers share structure, so a
 * snapshot is cheap); in-place ops never reorder elements, so the refs stay valid across a live
 * drag. Free of Android types — unit-testable on the JVM.
 *
 * **Round-trip scope.** The `.xopp` format stores only what each element carries on disk, so an op
 * exists only where it round-trips: scale bakes into coordinates/box/font-size, rotate bakes into a
 * stroke's vertex coordinates (the only element with a free-form point list). Text and images have
 * no rotation attribute and axis-aligned boxes, so [rotate] leaves them untouched — the view offers
 * the rotate handle only for all-stroke selections (see `docs/architecture.md`).
 */
object SelectionOps {

    /** Shift a single element by (dx, dy) pt, preserving everything else about it. */
    fun translate(element: Element, dx: Double, dy: Double): Element = when (element) {
        is Stroke -> element.copy(points = element.points.map { StrokePoint(it.x + dx, it.y + dy, it.width) })
        is TextElement -> element.copy(x = element.x + dx, y = element.y + dy)
        is ImageElement -> element.copy(
            left = element.left + dx, top = element.top + dy,
            right = element.right + dx, bottom = element.bottom + dy,
        )
        is TexImageElement -> element.copy(
            left = element.left + dx, top = element.top + dy,
            right = element.right + dx, bottom = element.bottom + dy,
        )
    }

    /** Return [pages] with the elements at [refs] on page [pageIndex] shifted by (dx, dy) pt. */
    fun translate(pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>, dx: Double, dy: Double): List<Page> {
        if (refs.isEmpty() || (dx == 0.0 && dy == 0.0)) return pages
        return mapPage(pages, pageIndex) { li, ei, el ->
            if (ElementRef(li, ei) in refs) translate(el, dx, dy) else el
        }
    }

    /**
     * Apply the affine `x' = x·s + dx`, `y' = y·s + dy` to one element; scalar sizes (a stroke's
     * per-vertex width, a text box's font size) scale by [s]. This single primitive backs both
     * uniform resize (a scale about an anchor) and cross-page re-homing (a scale+shift between two
     * pages' pt frames). [s] is expected positive so image/box corner order is preserved.
     */
    fun affine(element: Element, s: Double, dx: Double, dy: Double): Element = when (element) {
        is Stroke -> element.copy(points = element.points.map { StrokePoint(it.x * s + dx, it.y * s + dy, it.width * s) })
        is TextElement -> element.copy(x = element.x * s + dx, y = element.y * s + dy, size = element.size * s)
        is ImageElement -> element.copy(
            left = element.left * s + dx, top = element.top * s + dy,
            right = element.right * s + dx, bottom = element.bottom * s + dy,
        )
        is TexImageElement -> element.copy(
            left = element.left * s + dx, top = element.top * s + dy,
            right = element.right * s + dx, bottom = element.bottom * s + dy,
        )
    }

    /**
     * Return [pages] with the elements at [refs] on page [pageIndex] uniformly scaled by [factor]
     * about the anchor point (`anchorX`, `anchorY`) pt — the opposite corner of a resize drag, which
     * stays fixed. [factor] should be positive.
     */
    fun scale(
        pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>,
        factor: Double, anchorX: Double, anchorY: Double,
    ): List<Page> {
        if (refs.isEmpty() || factor == 1.0) return pages
        val dx = anchorX * (1.0 - factor)
        val dy = anchorY * (1.0 - factor)
        return mapPage(pages, pageIndex) { li, ei, el ->
            if (ElementRef(li, ei) in refs) affine(el, factor, dx, dy) else el
        }
    }

    /** Rotate a single element by [angle] rad about the pivot (`px`, `py`) pt. See the class note:
     * only a [Stroke] carries a rotatable point list, so text/images are returned unchanged. */
    fun rotate(element: Element, angle: Double, px: Double, py: Double): Element = when (element) {
        is Stroke -> {
            val c = cos(angle); val s = sin(angle)
            element.copy(points = element.points.map {
                val rx = it.x - px; val ry = it.y - py
                StrokePoint(px + rx * c - ry * s, py + rx * s + ry * c, it.width)
            })
        }
        else -> element
    }

    /** Return [pages] with the strokes at [refs] on page [pageIndex] rotated by [angle] rad about
     * the pivot (`pivotX`, `pivotY`) pt. */
    fun rotate(
        pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>,
        angle: Double, pivotX: Double, pivotY: Double,
    ): List<Page> {
        if (refs.isEmpty() || angle == 0.0) return pages
        return mapPage(pages, pageIndex) { li, ei, el ->
            if (ElementRef(li, ei) in refs) rotate(el, angle, pivotX, pivotY) else el
        }
    }

    /** Recolour ([color]) and/or re-width ([widthPt]) one element. Width applies to strokes only
     * (as a uniform width); colour applies to strokes/text/LaTeX (images have no colour). A null
     * field leaves that property unchanged. */
    fun restyle(element: Element, color: Int?, widthPt: Double?): Element = when (element) {
        is Stroke -> {
            var e = if (color != null) element.copy(color = color) else element
            if (widthPt != null) e = e.copy(points = e.points.map { StrokePoint(it.x, it.y, widthPt) }, uniformWidth = true)
            e
        }
        is TextElement -> if (color != null) element.copy(color = color) else element
        is TexImageElement -> if (color != null) element.copy(color = color) else element
        is ImageElement -> element
    }

    /** Return [pages] with the elements at [refs] on page [pageIndex] recoloured/re-widthed. */
    fun restyle(
        pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>, color: Int?, widthPt: Double?,
    ): List<Page> {
        if (refs.isEmpty() || (color == null && widthPt == null)) return pages
        return mapPage(pages, pageIndex) { li, ei, el ->
            if (ElementRef(li, ei) in refs) restyle(el, color, widthPt) else el
        }
    }

    /** The elements named by [refs] on [page], in a stable layer-then-index order (for copy/cut). */
    fun elementsAt(page: Page, refs: Set<ElementRef>): List<Element> =
        refs.sortedWith(compareBy({ it.layerIndex }, { it.elementIndex }))
            .mapNotNull { page.layers.getOrNull(it.layerIndex)?.elements?.getOrNull(it.elementIndex) }

    /**
     * Append [elements] to page [pageIndex]'s top (last) layer — creating a layer if the page has
     * none — and return the new page list paired with the [ElementRef]s of the appended elements
     * (used to select a freshly pasted/duplicated set).
     */
    fun addToTopLayer(
        pages: List<Page>, pageIndex: Int, elements: List<Element>,
    ): Pair<List<Page>, Set<ElementRef>> {
        val page = pages.getOrNull(pageIndex) ?: return pages to emptySet()
        if (elements.isEmpty()) return pages to emptySet()
        val layers = page.layers
        val topIndex = (layers.size - 1).coerceAtLeast(0)
        val base = layers.lastOrNull()?.elements?.size ?: 0
        val newLayers = if (layers.isEmpty()) {
            listOf(Layer(elements.toList()))
        } else {
            layers.toMutableList().also { it[topIndex] = Layer(it[topIndex].elements + elements, it[topIndex].name) }
        }
        val refs = elements.indices.map { ElementRef(topIndex, base + it) }.toSet()
        val out = pages.toMutableList().also { it[pageIndex] = page.copy(layers = newLayers) }
        return out to refs
    }

    /**
     * Move the elements at [refs] from page [from] to page [to], applying the affine
     * `x' = x·s + dx`, `y' = y·s + dy` so they land where the drop point is in the target page's pt
     * frame (equal-width pages → a pure shift; differing widths → also rescaled to keep the visual
     * position). Returns the new page list and the refs of the moved elements on [to].
     */
    fun moveToPage(
        pages: List<Page>, from: Int, to: Int, refs: Set<ElementRef>, s: Double, dx: Double, dy: Double,
    ): Pair<List<Page>, Set<ElementRef>> {
        if (from == to || refs.isEmpty()) return pages to refs
        val src = pages.getOrNull(from) ?: return pages to refs
        val moved = elementsAt(src, refs).map { affine(it, s, dx, dy) }
        val afterDelete = delete(pages, from, refs)
        return addToTopLayer(afterDelete, to, moved)
    }

    /** Return [pages] with the elements at [refs] on page [pageIndex] removed. */
    fun delete(pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>): List<Page> {
        if (refs.isEmpty()) return pages
        val page = pages.getOrNull(pageIndex) ?: return pages
        val layers = page.layers.mapIndexed { li, layer ->
            val kept = layer.elements.filterIndexed { ei, _ -> ElementRef(li, ei) !in refs }
            if (kept.size == layer.elements.size) layer else Layer(kept, layer.name)
        }
        return pages.toMutableList().also { it[pageIndex] = page.copy(layers = layers) }
    }

    /** Rebuild page [pageIndex]'s layers, letting [transform] replace each element in place. */
    private inline fun mapPage(
        pages: List<Page>,
        pageIndex: Int,
        transform: (layerIndex: Int, elementIndex: Int, element: Element) -> Element,
    ): List<Page> {
        val page = pages.getOrNull(pageIndex) ?: return pages
        val layers = page.layers.mapIndexed { li, layer ->
            Layer(layer.elements.mapIndexed { ei, el -> transform(li, ei, el) }, layer.name)
        }
        return pages.toMutableList().also { it[pageIndex] = page.copy(layers = layers) }
    }
}
