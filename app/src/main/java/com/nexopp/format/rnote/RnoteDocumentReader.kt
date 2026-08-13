package com.nexopp.format.rnote

import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement

/**
 * The assembler that ties the `.rnote` import together: it takes the [RnoteSnapshot] intermediate
 * model and hands back a [Document]. Every rule it applies is decided elsewhere — the per-stroke
 * mapping in `RnoteStrokeConvert.kt`, the page geometry in `RnotePagination.kt`, the layer stack in
 * `RnoteLayerStack.kt` and the background in `RnoteBackgroundMapper.kt` — so this file is only the
 * wiring: convert, paginate, bucket, build. See `docs/architecture.md`, "Decision (2026-08-12):
 * canvas ↔ pages, layers & backgrounds".
 */

/** The slot a blank canvas's single layer takes, so an imported empty file is still drawable. */
private const val DEFAULT_SLOT = "user_layer 0"

/**
 * A converted `.rnote` file: the document plus what could not cross into it.
 *
 * @property document The imported document, one page per canvas page-height of content.
 * @property skipped One ready-to-show line per unconvertible stroke kind, e.g.
 *   `2 vectorimage strokes could not be converted`. Empty when nothing was lost. The
 *   lossy-mapping policy in `docs/architecture.md` forbids dropping this silently — whoever calls
 *   the reader must surface it.
 */
data class RnoteImport(val document: Document, val skipped: List<String>)

/**
 * Assemble a [Document] from a parsed snapshot.
 *
 * The canvas is cut into fixed-size pages by the strokes' own bounding box, every page gets the
 * same layer stack (empty layers included, so page-to-page layer indices line up) and the same
 * background. Strokes arrive already in painters order, so appending them preserves it.
 *
 * @param snapshot The parsed `engine_snapshot`, from [RnoteSnapshot.parse].
 * @return The document and the report of strokes that could not be converted.
 */
fun readDocument(snapshot: RnoteSnapshot): RnoteImport {
    val skipped = LinkedHashMap<String, Int>()
    val placed = ArrayList<PlacedElement>(snapshot.strokes.size)
    for (stroke in snapshot.strokes) {
        val element = convertStrokeOrNull(stroke)
        if (element == null) {
            skipped[stroke.kind] = (skipped[stroke.kind] ?: 0) + 1
        } else {
            placed += PlacedElement(layerSlotName(stroke), element, elementBoundsPx(element))
        }
    }
    val layout = pageLayout(snapshot.format, unionBounds(placed.mapNotNull { it.bounds }))
    val slots = layerSlots(snapshot.strokes).ifEmpty { listOf(DEFAULT_SLOT) }
    val buckets = fillPages(placed, slots, layout, snapshot.format)
    return RnoteImport(
        document = Document(creator = "NeXopp", pages = buildPages(buckets, slots, layout, snapshot)),
        skipped = skipped.map { (kind, count) -> report(kind, count) },
    )
}

/** One converted element with what the assembler needs to file it: its slot and its extent. */
private class PlacedElement(
    val slot: String,
    val element: Element,
    /** The element's bounding box in canvas px, or null for an element with no geometry. */
    val bounds: RnoteBounds?,
)

/**
 * Drop every element into its page and layer, translated to be page-local.
 *
 * @return A `[pageIndex][slotIndex]` grid of element lists, in painters order within each cell.
 */
private fun fillPages(
    placed: List<PlacedElement>,
    slots: List<String>,
    layout: RnotePageLayout,
    format: RnoteFormat,
): List<List<MutableList<Element>>> {
    val buckets = List(layout.pageCount) { List(slots.size) { ArrayList<Element>() } }
    for (item in placed) {
        val pageIndex = item.bounds?.let { pageIndexFor(it, layout, format) } ?: 0
        val (originX, originY) = pageOriginPx(pageIndex, layout, format)
        val slotIndex = slots.indexOf(item.slot).coerceAtLeast(0)
        buckets[pageIndex][slotIndex] += translate(item.element, pxToPt(originX), pxToPt(originY))
    }
    return buckets
}

/** Build the page stack: same size, same layer names and same background on every page. */
private fun buildPages(
    buckets: List<List<MutableList<Element>>>,
    slots: List<String>,
    layout: RnotePageLayout,
    snapshot: RnoteSnapshot,
): List<Page> {
    val background = toXoppBackground(snapshot.background)
    return buckets.map { page ->
        Page(
            width = layout.pageWidth,
            height = layout.pageHeight,
            background = background,
            layers = slots.mapIndexed { index, name -> Layer(page[index].toList(), name) },
        )
    }
}

/** One line of the conversion report, pluralised. */
private fun report(kind: String, count: Int): String =
    "$count $kind ${if (count == 1) "stroke" else "strokes"} could not be converted"

/**
 * An element's bounding box in canvas px — the converters already work in pt, so this is the
 * inverse scale. A text box has only an anchor, so its box is that single point; an element with
 * no geometry at all (a [com.nexopp.format.model.RawElement], which no converter produces) has
 * none and lands on page 1.
 */
private fun elementBoundsPx(element: Element): RnoteBounds? = when (element) {
    is Stroke -> element.points.takeIf { it.isNotEmpty() }?.let { points ->
        boundsPx(
            minX = points.minOf { it.x },
            minY = points.minOf { it.y },
            maxX = points.maxOf { it.x },
            maxY = points.maxOf { it.y },
        )
    }
    is TextElement -> boundsPx(element.x, element.y, element.x, element.y)
    is ImageElement -> boundsPx(element.left, element.top, element.right, element.bottom)
    is TexImageElement -> boundsPx(element.left, element.top, element.right, element.bottom)
    else -> null
}

/** A pt box as the canvas-px box the pagination works in. */
private fun boundsPx(minX: Double, minY: Double, maxX: Double, maxY: Double): RnoteBounds =
    RnoteBounds(ptToPx(minX), ptToPx(minY), ptToPx(maxX), ptToPx(maxY))

/** The smallest box containing all of [all], or null when there is nothing on the canvas. */
private fun unionBounds(all: List<RnoteBounds>): RnoteBounds? =
    if (all.isEmpty()) {
        null
    } else {
        RnoteBounds(
            minX = all.minOf { it.minX },
            minY = all.minOf { it.minY },
            maxX = all.maxOf { it.maxX },
            maxY = all.maxOf { it.maxY },
        )
    }

/** Shift an element by a pt offset, leaving everything else about it alone. */
private fun translate(element: Element, dx: Double, dy: Double): Element = when (element) {
    is Stroke -> element.copy(points = element.points.map { it.copy(x = it.x + dx, y = it.y + dy) })
    is TextElement -> element.copy(x = element.x + dx, y = element.y + dy)
    is ImageElement -> element.copy(
        left = element.left + dx,
        top = element.top + dy,
        right = element.right + dx,
        bottom = element.bottom + dy,
    )
    is TexImageElement -> element.copy(
        left = element.left + dx,
        top = element.top + dy,
        right = element.right + dx,
        bottom = element.bottom + dy,
    )
    else -> element
}
