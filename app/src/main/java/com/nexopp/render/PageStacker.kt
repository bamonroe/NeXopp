package com.nexopp.render

import com.nexopp.format.model.Page

/**
 * Placement of one page in the canvas, in view pixels. Each page is scaled to fit its column (the
 * view width divided by the column count, times the zoom factor); [topPx] is its top edge and
 * [leftPx] its left edge in content space (before scroll is applied). Rows are centred horizontally.
 */
data class PageBox(
    val index: Int,
    val topPx: Float,
    val leftPx: Float,
    val heightPx: Float,
    val scale: Float,
    val page: Page,
) {
    val widthPx: Float get() = (page.width * scale).toFloat()
    val bottomPx: Float get() = topPx + heightPx
    val rightPx: Float get() = leftPx + widthPx

    fun contains(contentX: Float, contentY: Float): Boolean =
        contentX >= leftPx && contentX < rightPx && contentY >= topPx && contentY < bottomPx

    /** View x (px, scrolled) → page x (pt), given the viewport's horizontal scroll in px. */
    fun toPtX(viewX: Float, scrollX: Float): Double = ((viewX + scrollX - leftPx) / scale).toDouble()

    /** View y (px, scrolled) → page y (pt), given the viewport's vertical scroll in px. */
    fun toPtY(viewY: Float, scrollY: Float): Double = ((viewY + scrollY - topPx) / scale).toDouble()

    /** Page x (pt) → view x (px), given the viewport's horizontal scroll in px. */
    fun toViewX(ptX: Double, scrollX: Float): Float = (ptX * scale + leftPx - scrollX).toFloat()

    /** Page y (pt) → view y (px), given the viewport's vertical scroll in px. */
    fun toViewY(ptY: Double, scrollY: Float): Float = (ptY * scale + topPx - scrollY).toFloat()
}

/**
 * The laid-out document: one [PageBox] per page, the total content height, the content width (the
 * widest row, or the view width if wider — used to bound horizontal panning), and how many pages sit
 * side by side in a row.
 */
data class StackedLayout(
    val boxes: List<PageBox>,
    val totalHeightPx: Float,
    val contentWidthPx: Float,
    val columns: Int = 1,
) {

    /** The page whose vertical band contains [contentY] (content space), or null in a gap. */
    fun pageAt(contentY: Float): PageBox? =
        boxes.firstOrNull { contentY >= it.topPx && contentY < it.bottomPx }

    /**
     * The page under the content-space point, or null if the point falls in a gap. With one column
     * this is [pageAt]; with several it is the only way to tell the columns apart.
     */
    fun pageAt(contentX: Float, contentY: Float): PageBox? =
        boxes.firstOrNull { it.contains(contentX, contentY) }

    /**
     * The page under the point, falling back to the nearest one in the same row (and then to the
     * band) so a point in a gap still resolves — used for "which page am I looking at".
     */
    fun nearestPage(contentX: Float, contentY: Float): PageBox? =
        pageAt(contentX, contentY)
            ?: boxes.filter { contentY >= it.topPx && contentY < it.bottomPx }
                .minByOrNull { minOf(kotlin.math.abs(contentX - it.leftPx), kotlin.math.abs(contentX - it.rightPx)) }

    /** Boxes that intersect the visible window `[scrollY, scrollY + viewHeightPx)`. */
    fun visible(scrollY: Float, viewHeightPx: Float): List<PageBox> =
        boxes.filter { it.bottomPx > scrollY && it.topPx < scrollY + viewHeightPx }
}

/**
 * Lays document pages out in rows of [columns] pages, top-to-bottom, each fit to its column width
 * scaled by [zoom] and separated by [gapPx]. One column is the plain vertical stack; two or more is
 * the page-overview grid. Rows are centred, and pages shorter than their row sit at the row's top.
 */
object PageStacker {

    /** Column counts the UI offers; index 0 is the plain single-page stack. */
    val COLUMN_CHOICES = listOf(1, 2, 3, 4)

    fun stack(
        pages: List<Page>,
        viewWidthPx: Int,
        gapPx: Float,
        zoom: Float = 1f,
        columns: Int = 1,
    ): StackedLayout {
        if (viewWidthPx <= 0) return StackedLayout(emptyList(), 0f, 0f, 1)
        val cols = columns.coerceIn(1, COLUMN_CHOICES.last())
        // Each column gets an equal share of the width left over once the gaps between the columns
        // are taken out; one column is therefore the full view width, as before.
        val cellWidth = ((viewWidthPx - gapPx * (cols - 1)) / cols).coerceAtLeast(1f)
        val placed = ArrayList<PageBox>(pages.size)
        var top = gapPx
        var contentWidth = viewWidthPx.toFloat()
        pages.chunked(cols).forEach { row ->
            val boxes = row.mapIndexed { col, page ->
                val scale = if (page.width > 0) (cellWidth / page.width).toFloat() * zoom else zoom
                PageBox(placed.size + col, top, 0f, (page.height * scale).toFloat(), scale, page)
            }
            val rowWidth = boxes.sumOf { it.widthPx.toDouble() }.toFloat() + gapPx * (boxes.size - 1)
            contentWidth = maxOf(contentWidth, rowWidth)
            var left = 0f
            boxes.forEach { placed += it.copy(leftPx = left); left += it.widthPx + gapPx }
            top += (boxes.maxOfOrNull { it.heightPx } ?: 0f) + gapPx
        }
        // Centre each row in the content band now that the widest row is known.
        val rowOffsets = placed.groupBy { it.topPx }.mapValues { (_, row) ->
            (contentWidth - (row.maxOf { it.rightPx } - row.minOf { it.leftPx })) / 2f
        }
        val centred = placed.map { it.copy(leftPx = it.leftPx + (rowOffsets[it.topPx] ?: 0f)) }
        return StackedLayout(centred, top, contentWidth, cols)
    }
}
