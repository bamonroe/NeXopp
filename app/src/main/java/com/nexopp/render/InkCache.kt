package com.nexopp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nexopp.format.model.Page

/**
 * Off-screen rasterisation of a page's ink, so panning and flinging blit a bitmap instead of
 * re-submitting every stroke to the canvas each frame.
 *
 * One bitmap per page index, rasterised at a **zoom bucket** rather than the exact on-screen size:
 * buckets step geometrically (see [BUCKET_RATIO]), so a pinch only re-rasterises when it crosses a
 * bucket edge and the in-between zooms are a scaled blit of at most ~19% stretch. An entry is
 * dropped when the page's content changes (page identity), when its hidden-layer set changes, or
 * when it leaves the viewport ([retain]).
 *
 * The cache **declines** any page whose bucket would exceed [budgetPx] — at deep zoom a page spans
 * many screens and its full raster would dwarf the screen it feeds. [draw] returns false there and
 * the caller falls back to drawing elements directly, where viewport culling already does the work.
 *
 * Every raster is charged to a shared [BitmapBudget], so this cache and [PdfPageCache] live under
 * one bound instead of two independent guesses; when the total goes over, [trim] hands back the
 * off-screen pages first.
 *
 * In **overview mode** (columns > 1), the cache tightens its bounds: bucket widths are capped and
 * the entry count is limited, so a multi-page grid on a large document can't blow the bitmap budget.
 */
class InkCache(
    private val budget: BitmapBudget = BitmapBudget.shared,
    private val columns: () -> Int = { 1 },
) : BitmapBudget.Client {

    /** Max pixels in one cached page raster, from this device's share of the shared budget. */
    private val budgetPx: Int =
        (budget.perEntryBytes(BitmapLruCache.PAGE_SHARE) / BYTES_PER_PX).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Max entries in overview mode; null in single-page mode (byte budget only). */
    private val maxEntries = { if (columns() > 1) OVERVIEW_MAX_ENTRIES else null }

    /** One cached raster: the bitmap, the page identity it was drawn from, and the hidden-layer set. */
    private class Entry(
        val bitmap: Bitmap,
        val page: Page,
        val hidden: Set<Int>,
        val widthPx: Int,
    )

    /** Guards [entries] and [retained]: [trim] can arrive from the PDF worker thread. */
    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    /** The pages last asked for by [retain] — what [trim] gives back last. */
    private var retained: Set<Int> = emptySet()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        budget.register(this)
    }

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
        val left = box.toViewX(0.0, scrollX)
        val top = box.toViewY(0.0, scrollY)
        dst.set(left, top, left + box.widthPx, top + box.heightPx)
        canvas.drawBitmap(entry.bitmap, null, dst, blit)
        return true
    }

    /** Drop every cached page not in [keep] — off-screen pages are the bulk of the memory. */
    fun retain(keep: Set<Int>) {
        var freed = 0L
        synchronized(lock) {
            retained = HashSet(keep)
            val gone = entries.keys.filter { it !in keep }
            for (i in gone) entries.remove(i)?.bitmap?.let { freed += it.byteCount.toLong(); it.recycle() }
        }
        if (freed > 0) budget.credit(freed)
    }

    /** Drop everything (surface teardown, document swap). */
    fun clear() = retain(emptySet())

    /**
     * Give [bytes] back to the shared budget, off-screen pages first. Bitmaps handed back here are
     * **not** recycled: unlike [retain] this can run on another cache's worker thread while the
     * drawing thread still holds the bitmap for the current frame.
     */
    override fun trim(bytes: Long): Long {
        var freed = 0L
        synchronized(lock) {
            for (pass in 0..1) {
                val it = entries.entries.iterator()
                while (freed < bytes && it.hasNext()) {
                    val e = it.next()
                    if (pass == 0 && e.key in retained) continue
                    freed += e.value.bitmap.byteCount.toLong()
                    it.remove()
                }
                if (freed >= bytes) break
            }
        }
        return freed
    }

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
        synchronized(lock) {
            // In overview mode, enforce the entry count limit by dropping the eldest entries.
            val limit = maxEntries()
            if (limit != null && entries.size >= limit) {
                val eldest = entries.entries.firstOrNull()?.key
                if (eldest != null && eldest != box.index) {
                    entries.remove(eldest)?.bitmap?.let { budget.credit(it.byteCount.toLong()); it.recycle() }
                }
            }
            val cached = entries[box.index]
            if (cached != null &&
                cached.page === box.page &&
                cached.widthPx == bucketW &&
                cached.hidden == hidden
            ) {
                return cached
            }
            entries.remove(box.index)?.bitmap?.let { budget.credit(it.byteCount.toLong()); it.recycle() }
        }
        val fresh = rasterise(box, hidden, bucketW, bucketH, strokes, elements) ?: return null
        synchronized(lock) { entries[box.index] = fresh }
        // Charged after the insert, so an over-budget total can evict this very entry if it must.
        budget.charge(this, fresh.bitmap.byteCount.toLong())
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
     * width alone. Buckets start at [BUCKET_BASE] and step by [BUCKET_RATIO]. In overview mode
     * (columns > 1), the width is capped to keep memory bounded across many visible pages.
     */
    private fun bucketWidth(widthPx: Float): Int? {
        var w = BUCKET_BASE.toFloat()
        val cap = if (columns() > 1) OVERVIEW_BUCKET_CAP.toFloat() else Float.POSITIVE_INFINITY
        while (w < widthPx) {
            w *= BUCKET_RATIO
            if (w > budgetPx || w > cap) return null
        }
        return w.toInt().coerceAtMost(cap.toInt())
    }

    private companion object {
        const val BYTES_PER_PX = 4

        /** Narrowest bucket; below this a page is a thumbnail and the raster is nearly free. */
        const val BUCKET_BASE = 256

        /** Geometric step between buckets: ≤19% upscale between rasterisations. */
        const val BUCKET_RATIO = 1.19f

        /** Max bucket width in overview mode (columns > 1), keeping previews memory-bounded. */
        private const val OVERVIEW_BUCKET_CAP = 200

        /** Max cached entries in overview mode; single-page mode is byte-budget only. */
        private const val OVERVIEW_MAX_ENTRIES = 12
    }
}
