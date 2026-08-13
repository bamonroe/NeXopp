package com.nexopp.format.rnote

import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.Tool

/**
 * The export-side geometry that lays our fixed-size pages back onto Rnote's single canvas — the
 * mirror of [pageLayout]/[layerSlots] on the import side. See `docs/architecture.md`, "Decision
 * (2026-08-12): canvas ↔ pages, layers & backgrounds" (the *Export* half) — this file is that rule
 * in code. It is pure geometry and slot assignment: no JSON, no strokes, no serialisation.
 */

/** A4 in Rnote px at 96 dpi — the format an empty document falls back to, as Rnote's own does. */
private const val A4_WIDTH_PX = 793.701

/** A4's height in Rnote px; see [A4_WIDTH_PX]. */
private const val A4_HEIGHT_PX = 1122.52

/** The only dpi Rnote writes, and the one [PX_PER_PT] assumes. */
private const val RNOTE_DPI = 96.0

/** Rnote's numbered pen slot; a [Layer.name] may claim it as `user_layer <n>`. */
private const val USER_LAYER = "user_layer"

/** The slots a [Layer.name] may name outright, other than a numbered [USER_LAYER]. */
private val NAMED_SLOTS = setOf("document", "image", "highlighter")

/**
 * The canvas format, taken from **page 1** and converted back into px: every page after it is
 * assumed to be the same size (see [hasMixedPageSizes] for the case where it isn't).
 *
 * @param document The document being exported.
 * @return The `document.format` block; A4 portrait when the document has no pages.
 */
fun canvasFormat(document: Document): RnoteFormat {
    val page = document.pages.firstOrNull()
        ?: return RnoteFormat(A4_WIDTH_PX, A4_HEIGHT_PX, RNOTE_DPI, "portrait")
    return RnoteFormat(
        width = ptToPx(page.width),
        height = ptToPx(page.height),
        dpi = RNOTE_DPI,
        orientation = if (page.width <= page.height) "portrait" else "landscape",
    )
}

/**
 * Where each page's top edge lands on the canvas: pages stack downwards with **no gap**, page 0 at
 * `0.0`. The offsets are cumulative rather than `i × format.height` so a document with mixed page
 * sizes still puts every stroke in the right place; for the uniform documents that are the norm the
 * two are identical.
 *
 * @param document The document being exported.
 * @return One canvas-px y offset per page, in page order; empty when the document has no pages.
 */
fun pageOriginsPx(document: Document): List<Double> {
    var y = 0.0
    return document.pages.map { page ->
        val origin = y
        y += ptToPx(page.height)
        origin
    }
}

/**
 * The canvas rectangle the page stack fills: the origin down to the widest page and the full stack
 * height. The writer emits `layout: "fixed_size"` (not the `infinite` `rnote-cli` writes) so Rnote
 * draws its page boundaries exactly where this stack puts them.
 *
 * @param document The document being exported.
 * @return `document.x/y/width/height` as a box anchored at the origin; one A4 page when the
 *   document has no pages, matching [canvasFormat]'s fallback.
 */
fun canvasExtent(document: Document): RnoteBounds {
    if (document.pages.isEmpty()) {
        return RnoteBounds(minX = 0.0, minY = 0.0, maxX = A4_WIDTH_PX, maxY = A4_HEIGHT_PX)
    }
    return RnoteBounds(
        minX = 0.0,
        minY = 0.0,
        maxX = ptToPx(document.pages.maxOf { it.width }),
        maxY = ptToPx(document.pages.sumOf { it.height }),
    )
}

/**
 * The Rnote layer slot an element collapses onto. Our layers are a free-form stack and Rnote's are
 * four fixed slots, so the choice is made in order: a highlighter stroke and an image go to the
 * slots Rnote reserves for them whatever layer they sit on; otherwise a layer that names one of
 * Rnote's own slots (as an imported document's layers do) is taken at its word; otherwise the
 * layer's position in the stack becomes its pen-layer number.
 *
 * @param element The element being written.
 * @param layer The layer it sits on.
 * @param layerIndex That layer's index in its page's stack, bottom first.
 * @return The `layer` name and the `user_layer` number, the latter null for every fixed slot.
 */
fun slotFor(element: Element, layer: Layer, layerIndex: Int): Pair<String, Int?> {
    if (element is Stroke && element.tool == Tool.HIGHLIGHTER) return "highlighter" to null
    if (element is ImageElement) return "image" to null
    val name = layer.name
    if (name != null) {
        if (name in NAMED_SLOTS) return name to null
        userLayerNumber(name)?.let { return USER_LAYER to it }
    }
    return USER_LAYER to layerIndex
}

/**
 * Whether [name] is one of Rnote's own slot names — the fixed three, or the pen slot in its
 * numbered `user_layer <n>` spelling. A layer named anything else keeps its content but loses its
 * name on export, which is why `RnoteExportWarnings` asks.
 *
 * @param name A [Layer.name] from the document being exported.
 * @return True when [slotFor] would take the name at its word rather than renumbering it.
 */
fun isSlotName(name: String): Boolean = name in NAMED_SLOTS || userLayerNumber(name) != null

/** The `n` in a `user_layer <n>` layer name, or null when the name isn't that. */
private fun userLayerNumber(name: String): Int? =
    if (!name.startsWith("$USER_LAYER ")) null
    else name.removePrefix("$USER_LAYER ").toIntOrNull()?.takeIf { it >= 0 }

/**
 * Whether any page differs in size from page 1. Such a document still *exports* with every stroke
 * in the right place, but re-importing it cuts the canvas at page 1's height and so re-pages it
 * wrongly — a known lossy case the save path warns about.
 *
 * @param document The document being exported.
 * @return True when the page sizes are not uniform; false for an empty or single-page document.
 */
fun hasMixedPageSizes(document: Document): Boolean {
    val first = document.pages.firstOrNull() ?: return false
    return document.pages.any { it.width != first.width || it.height != first.height }
}
