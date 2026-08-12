package com.nexopp.format.rnote

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The import-side geometry that cuts Rnote's single canvas into fixed-size `.xopp` pages. See
 * `docs/architecture.md`, "Decision (2026-08-12): canvas ↔ pages, layers & backgrounds" — this file
 * is that rule in code. It works on axis-aligned bounding boxes alone, so it knows nothing about
 * strokes.
 */

/**
 * An axis-aligned bounding box in Rnote canvas px, y growing downwards.
 */
data class RnoteBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)

/**
 * How one canvas becomes a page stack.
 *
 * @property pageWidth Every page's width in pt.
 * @property pageHeight Every page's height in pt.
 * @property pageCount How many pages the content needs — always at least one.
 * @property shiftX Canvas px to add to every x so nothing sits left of the origin.
 * @property shiftY Canvas px to add to every y so nothing sits above the origin.
 */
data class RnotePageLayout(
    val pageWidth: Double,
    val pageHeight: Double,
    val pageCount: Int,
    val shiftX: Double,
    val shiftY: Double,
)

/**
 * Work out the page stack for a canvas.
 *
 * `document.x/y/width/height` and `document.config.layout` are deliberately ignored: in Rnote's
 * infinite layouts those are viewport bounds, not content bounds, so the page count comes from the
 * strokes' own bounding box instead.
 *
 * @param format The canvas page format, in Rnote px.
 * @param contentBounds The bounding box of every stroke on the canvas, or null when it is empty.
 * @return The page size, page count and origin shift to apply to every stroke.
 */
fun pageLayout(format: RnoteFormat, contentBounds: RnoteBounds?): RnotePageLayout {
    val shiftX = if (contentBounds == null) 0.0 else -minOf(0.0, contentBounds.minX)
    val shiftY = if (contentBounds == null) 0.0 else -minOf(0.0, contentBounds.minY)
    val pageCount = if (contentBounds == null) {
        1
    } else {
        max(1, ceil((contentBounds.maxY + shiftY) / format.height).toInt())
    }
    return RnotePageLayout(
        pageWidth = pxToPt(format.width),
        pageHeight = pxToPt(format.height),
        pageCount = pageCount,
        shiftX = shiftX,
        shiftY = shiftY,
    )
}

/**
 * Which page a stroke lands on: the one containing the **top edge** of its bounding box. A stroke
 * that straddles a boundary is never split — it stays whole on its starting page and overhangs.
 *
 * @param bounds The stroke's bounding box in canvas px.
 * @param layout The page stack from [pageLayout].
 * @param format The canvas page format, in Rnote px.
 * @return The page index, clamped into the stack.
 */
fun pageIndexFor(bounds: RnoteBounds, layout: RnotePageLayout, format: RnoteFormat): Int {
    val index = floor((bounds.minY + layout.shiftY) / format.height).toInt()
    return index.coerceIn(0, layout.pageCount - 1)
}

/**
 * The canvas-px translation that makes a page's strokes page-local. It already folds the origin
 * shift in, so a caller **adds** this pair to a canvas coordinate and then divides by [PX_PER_PT]
 * to land in `.xopp` pt: `xPt = (x + originX) / PX_PER_PT`.
 *
 * @param pageIndex The page from [pageIndexFor].
 * @param layout The page stack from [pageLayout].
 * @param format The canvas page format, in Rnote px.
 * @return The (x, y) px offset that maps canvas coordinates onto that page.
 */
fun pageOriginPx(pageIndex: Int, layout: RnotePageLayout, format: RnoteFormat): Pair<Double, Double> =
    layout.shiftX to (layout.shiftY - pageIndex * format.height)
