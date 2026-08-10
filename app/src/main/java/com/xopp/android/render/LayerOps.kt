package com.xopp.android.render

import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

/**
 * Pure layer-list edits for the layer-management UI: add, delete, rename, reorder, and move a
 * selection between layers. Each returns a new [Page] (immutable layers share structure, so a
 * document snapshot stays cheap) and never touches element order within a layer. Android-free and
 * unit-tested ([LayerOpsTest]). Layer *visibility* is not modelled here — it is a view-only editor
 * state (the `.xopp` format has no visibility attribute), so hiding a layer never changes the page.
 */
object LayerOps {

    /**
     * Add a fresh empty layer above the current top (last = topmost z-order) and return it + index.
     * @param page Page to add layer to.
     * @param name Optional layer name.
     * @return Pair of (new page, index of new layer).
     */
    fun add(page: Page, name: String? = null): Pair<Page, Int> {
        val layers = page.layers + Layer(emptyList(), name)
        return page.copy(layers = layers) to layers.lastIndex
    }

    /**
     * Remove layer [index]; the last remaining layer is never removed (a page keeps ≥ 1 layer).
     * @param page Page to remove layer from.
     * @param index Layer index to remove (0-based).
     * @return New page with layer removed, or unchanged if invalid.
     */
    fun remove(page: Page, index: Int): Page {
        if (page.layers.size <= 1 || index !in page.layers.indices) return page
        return page.copy(layers = page.layers.toMutableList().apply { removeAt(index) })
    }

    /**
     * Rename layer [index] to [name] (blank clears the custom name back to null).
     * @param page Page to rename layer in.
     * @param index Layer index to rename (0-based).
     * @param name New name, or blank to clear.
     * @return New page with renamed layer, or unchanged if invalid index.
     */
    fun rename(page: Page, index: Int, name: String): Page {
        if (index !in page.layers.indices) return page
        val clean = name.trim().ifEmpty { null }
        val layers = page.layers.toMutableList()
        layers[index] = layers[index].copy(name = clean)
        return page.copy(layers = layers)
    }

    /**
     * Merge layer [index] into the layer below it: the upper layer's elements are appended after
     * the lower one's (so z-order within the page is preserved) and the now-empty upper layer is
     * removed. The lower layer keeps its own name. No-op for the bottom layer or a bad index.
     * @param page Page to merge layers in.
     * @param index Layer index to merge down (0-based, cannot be 0).
     * @return New page with layers merged, or unchanged if invalid.
     */
    fun mergeDown(page: Page, index: Int): Page {
        if (index !in page.layers.indices || index == 0) return page
        val below = page.layers[index - 1]
        val merged = Layer(below.elements + page.layers[index].elements, below.name)
        val layers = page.layers.toMutableList()
        layers[index - 1] = merged
        layers.removeAt(index)
        return page.copy(layers = layers)
    }

    /**
     * Move layer [from] to position [to], shifting the others (used to reorder z-order).
     * @param page Page to reorder layers in.
     * @param from Source layer index (0-based).
     * @param to Destination layer index (0-based).
     * @return New page with reordered layers, or unchanged if invalid.
     */
    fun move(page: Page, from: Int, to: Int): Page {
        val n = page.layers.size
        if (from !in 0 until n || to !in 0 until n || from == to) return page
        val layers = page.layers.toMutableList()
        layers.add(to, layers.removeAt(from))
        return page.copy(layers = layers)
    }

    /**
     * Move the elements at [refs] onto layer [target], appending them (in stable layer-then-index
     * order) to that layer and removing them from wherever they were. Returns the page and the
     * refs of the moved elements on [target] (so the caller can keep them selected). Refs already
     * on [target] are left in place.
     * @param page Page to move elements within.
     * @param refs Set of element references to move.
     * @param target Target layer index (0-based).
     * @return Pair of (new page, refs of moved elements on target layer).
     */
    fun moveElementsToLayer(page: Page, refs: Set<ElementRef>, target: Int): Pair<Page, Set<ElementRef>> {
        if (target !in page.layers.indices || refs.isEmpty()) return page to refs
        val ordered = refs.sortedWith(compareBy({ it.layerIndex }, { it.elementIndex }))
        val moving = ordered.mapNotNull { r ->
            page.layers.getOrNull(r.layerIndex)?.elements?.getOrNull(r.elementIndex)
        }
        if (moving.isEmpty()) return page to refs
        val base = page.layers[target].elements.size
        val layers = page.layers.mapIndexed { li, layer ->
            val kept = layer.elements.filterIndexed { ei, _ -> ElementRef(li, ei) !in refs }
            Layer(if (li == target) kept + moving else kept, layer.name)
        }
        val newRefs = moving.indices.map { ElementRef(target, base + it) }.toSet()
        return page.copy(layers = layers) to newRefs
    }

    /**
     * A human-facing label for layer [index]: its custom name, or "Layer N" (1-based, bottom-up).
     * @param page Page to read layer from.
     * @param index Layer index (0-based).
     * @return Display name for the layer.
     */
    fun label(page: Page, index: Int): String =
        page.layers.getOrNull(index)?.name ?: "Layer ${index + 1}"
}
