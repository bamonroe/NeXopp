package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.xopp.android.format.model.Page

/**
 * Off-screen rasterisation of a page's ink, so panning and flinging blit a bitmap instead of
 * re-submitting every stroke to the canvas each frame.
 *
 * One bitmap per page index, rasterised at a **zoom bucket** rather than the exact on-screen size:
 * bucket widths step geometrically (see [BUCKET_RATIO]), so a pinch only re-rasterises when it
 * crosses a bucket edge and the in-between zooms are a scaled blit of at most ~19% stretch. An
 * entry is dropped when the page's content changes (page identity), when its hidden-layer set
 * changes, or when it leaves the viewport ([retain]).
 *
 * The cache **declines** any page whose bucket would exceed [BUDGET_PX] — at deep zoom a page spans
 * many screens and its full raster would dwarf the screen it feeds. [draw] returns false there and
 * the caller falls back to drawing elements directly, where viewport culling already does the work.
 */
class InkCache(private val budgetPx: Int = BUDGET_PX) {

    private class Entry(
        val bitmap: Bitmap,
        val page: Page,
        val hidden: Set<Int>,
        val widthPx: Int,
    )

    private val entries = HashMap<Int, Entry>()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /**
     * Blit page [box]'s ink at its on-screen position, rasterising it first if needed. Returns false
     * if this page can't be cached at this zoom — the caller must then draw its elements directly.
     */
    fun draw(
        canvas: Canvas,
        box: PageBox,
        scrollX: Float,
        scrollY: Float,
        hidden: Set<Int>,
        strokes: StrokePainter,
        elements: ElementRenderer,
    ): Boolean {
        val entry = entryFor(box, hidden, strokes, elements) ?: return false
        val left = box.leftPx - scrollX
        val top = box.topPx - scrollY
        dst.set(left, top, left + box.widthPx, top + box.heightPx)
        canvas.drawBitmap(entry.bitmap, null, dst, blit)
        return true
    }

    /** Drop every cached page not in [keep] — off-screen pages are the bulk of the memory. */
    fun retain(keep: Set<Int>) {
        val gone = entries.keys.filter { it !in keep }
        for (i in gone) entries.remove(i)?.bitmap?.recycle()
    }

    /** Drop everything (surface teardown, document swap). */
    fun clear() = retain(emptySet())

    /** The live entry for [box], rasterising on a miss. Null when the page exceeds the budget. */
    private fun entryFor(
        box: PageBox,
        hidden: Set<Int>,
        strokes: StrokePainter,
        elements: ElementRenderer,
    ): Entry? {
        if (box.widthPx <= 0f || box.heightPx <= 0f) return null
        val bucketW = bucketWidth(box.widthPx) ?: return null
        val bucketH = ((box.heightPx / box.widthPx) * bucketW).toInt().coerceAtLeast(1)
        if (bucketW.toLong() * bucketH > budgetPx) return null
        val cached = entries[box.index]
        if (cached != null &&
            cached.page === box.page &&
            cached.widthPx == bucketW &&
            cached.hidden == hidden
        ) {
            return cached
        }
        cached?.bitmap?.recycle()
        entries.remove(box.index)
        val fresh = rasterise(box, hidden, bucketW, bucketH, strokes, elements) ?: return null
        entries[box.index] = fresh
        return fresh
    }

    private fun rasterise(
        box: PageBox,
        hidden: Set<Int>,
        w: Int,
        h: Int,
        strokes: StrokePainter,
        elements: ElementRenderer,
    ): Entry? {
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val scale = (w / box.page.width).toFloat()
        PageRenderer.drawElements(
            Canvas(bitmap), box.page, scale, 0f, 0f, strokes, elements, hidden,
        )
        return Entry(bitmap, box.page, HashSet(hidden), w)
    }

    /**
     * The smallest bucket width at or above [widthPx], or null once that would blow the budget on
     * width alone. Buckets start at [BUCKET_BASE] and step by [BUCKET_RATIO].
     */
    private fun bucketWidth(widthPx: Float): Int? {
        var w = BUCKET_BASE.toFloat()
        while (w < widthPx) {
            w *= BUCKET_RATIO
            if (w > budgetPx) return null
        }
        return w.toInt()
    }

    private companion object {
        /** Max pixels in one cached page raster (~12 MB at ARGB_8888). */
        const val BUDGET_PX = 3_000_000

        /** Narrowest bucket; below this a page is a thumbnail and the raster is nearly free. */
        const val BUCKET_BASE = 256

        /** Geometric step between buckets: ≤19% upscale between rasterisations. */
        const val BUCKET_RATIO = 1.19f
    }
}
