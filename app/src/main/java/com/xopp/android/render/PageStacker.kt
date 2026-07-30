package com.xopp.android.render

import com.xopp.android.format.model.Page

/**
 * Placement of one page in the vertically-stacked canvas, in view pixels. Each page is scaled to
 * fit the view width; [topPx] is its top edge in content space (before scroll is applied).
 */
data class PageBox(
    val index: Int,
    val topPx: Float,
    val heightPx: Float,
    val scale: Float,
    val page: Page,
) {
    val widthPx: Float get() = (page.width * scale).toFloat()
    val bottomPx: Float get() = topPx + heightPx
}

/** The stacked layout of a whole document: one [PageBox] per page plus the total content height. */
data class StackedLayout(val boxes: List<PageBox>, val totalHeightPx: Float) {

    /** The page whose vertical band contains [contentY] (content space), or null in a gap. */
    fun pageAt(contentY: Float): PageBox? =
        boxes.firstOrNull { contentY >= it.topPx && contentY < it.bottomPx }

    /** Boxes that intersect the visible window `[scrollY, scrollY + viewHeightPx)`. */
    fun visible(scrollY: Float, viewHeightPx: Float): List<PageBox> =
        boxes.filter { it.bottomPx > scrollY && it.topPx < scrollY + viewHeightPx }
}

/** Lays document pages out top-to-bottom, each fit to the view width, separated by [gapPx]. */
object PageStacker {

    fun stack(pages: List<Page>, viewWidthPx: Int, gapPx: Float): StackedLayout {
        if (viewWidthPx <= 0) return StackedLayout(emptyList(), 0f)
        val boxes = ArrayList<PageBox>(pages.size)
        var top = gapPx
        pages.forEachIndexed { i, page ->
            val scale = if (page.width > 0) (viewWidthPx / page.width).toFloat() else 1f
            val heightPx = (page.height * scale).toFloat()
            boxes += PageBox(i, top, heightPx, scale, page)
            top += heightPx + gapPx
        }
        return StackedLayout(boxes, top)
    }
}
