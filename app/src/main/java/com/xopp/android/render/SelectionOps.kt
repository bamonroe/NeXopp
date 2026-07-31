package com.xopp.android.render

import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement

/**
 * Pure edits the selection tool applies to a page list: translate or delete the elements named by
 * a set of [ElementRef]s on one page. Both return a new page list (immutable pages/layers share
 * structure, so a snapshot is cheap) and never reorder elements, so the refs stay valid across a
 * live drag. Free of Android types — unit-testable on the JVM.
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

    /** Return [pages] with the elements at [refs] on page [pageIndex] removed. */
    fun delete(pages: List<Page>, pageIndex: Int, refs: Set<ElementRef>): List<Page> {
        if (refs.isEmpty()) return pages
        val page = pages.getOrNull(pageIndex) ?: return pages
        val layers = page.layers.mapIndexed { li, layer ->
            val kept = layer.elements.filterIndexed { ei, _ -> ElementRef(li, ei) !in refs }
            if (kept.size == layer.elements.size) layer else Layer(kept)
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
            Layer(layer.elements.mapIndexed { ei, el -> transform(li, ei, el) })
        }
        return pages.toMutableList().also { it[pageIndex] = page.copy(layers = layers) }
    }
}
