package com.xopp.android.render

import com.xopp.android.format.model.Page

/**
 * Placement of one page in the vertically-stacked canvas, in view pixels. Each page is scaled to
 * fit the view width (times the zoom factor); [topPx] is its top edge and [leftPx] its left edge in
 * content space (before scroll is applied). Pages narrower than the content band are centred.
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
}

/**
 * The stacked layout of a whole document: one [PageBox] per page, the total content height, and the
 * content width (the widest page, or the view width if wider — used to bound horizontal panning).
 */
data class StackedLayout(val boxes: List<PageBox>, val totalHeightPx: Float, val contentWidthPx: Float) {

    /** The page whose vertical band contains [contentY] (content space), or null in a gap. */
    fun pageAt(contentY: Float): PageBox? =
        boxes.firstOrNull { contentY >= it.topPx && contentY < it.bottomPx }

    /** Boxes that intersect the visible window `[scrollY, scrollY + viewHeightPx)`. */
    fun visible(scrollY: Float, viewHeightPx: Float): List<PageBox> =
        boxes.filter { it.bottomPx > scrollY && it.topPx < scrollY + viewHeightPx }
}

/**
 * Lays document pages out top-to-bottom, each fit to the view width scaled by [zoom], separated by
 * [gapPx]. Pages narrower than the content band (a zoomed-out or mixed-width document) are centred.
 */
object PageStacker {

    fun stack(pages: List<Page>, viewWidthPx: Int, gapPx: Float, zoom: Float = 1f): StackedLayout {
        if (viewWidthPx <= 0) return StackedLayout(emptyList(), 0f, 0f)
        val placed = ArrayList<PageBox>(pages.size)
        var top = gapPx
        var maxWidth = 0f
        pages.forEachIndexed { i, page ->
            val scale = if (page.width > 0) (viewWidthPx / page.width).toFloat() * zoom else zoom
            val heightPx = (page.height * scale).toFloat()
            placed += PageBox(i, top, 0f, heightPx, scale, page)
            maxWidth = maxOf(maxWidth, (page.width * scale).toFloat())
            top += heightPx + gapPx
        }
        val contentWidth = maxOf(viewWidthPx.toFloat(), maxWidth)
        val centred = placed.map { it.copy(leftPx = (contentWidth - it.widthPx) / 2f) }
        return StackedLayout(centred, top, contentWidth)
    }
}
