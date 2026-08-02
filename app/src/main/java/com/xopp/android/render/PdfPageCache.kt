package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Rasterises the pages of a single PDF to bitmaps for use as page backgrounds. Wraps the framework
 * [PdfRenderer] (dependency-free, API 21+ — see the dependency-free goal in `docs/architecture.md`).
 * `PdfRenderer` is not thread-safe and only one page may be open at a time, so every access is
 * serialised on [lock].
 *
 * Rendering is the expensive part of scrolling a long PDF-backed document, so the cache is built to
 * keep it off the drawing frame:
 *  - entries are keyed by (page, target-width bucket) and evicted **least-recently-used** under a
 *    heap-proportional byte budget, so the pages you are actually scrolling through survive;
 *  - [request] never rasterises on the calling thread: it returns the best bitmap already cached for
 *    that page (any width — the renderer scales it) and queues the exact size on a worker, calling
 *    [onPageReady] when the sharp version lands;
 *  - [prefetch] warms pages just outside the viewport so scrolling meets a filled cache.
 */
class PdfPageCache(val source: File) : Closeable {

    private val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Any()
    /** Access-ordered so eviction drops the least recently *used* entry, not the oldest. */
    private val cache = LinkedHashMap<Long, Bitmap>(16, 0.75f, true)
    private var cachedBytes = 0L
    private val sizes = HashMap<Int, Pair<Double, Double>>()
    private val pending = LinkedHashSet<Long>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-raster").apply { isDaemon = true } }
    private var closed = false

    /** Invoked (on the worker thread) whenever a newly rasterised page enters the cache. */
    @Volatile var onPageReady: (() -> Unit)? = null

    val pageCount: Int get() = renderer.pageCount

    /** Page [i]'s (width, height) in points (1/72"), the same unit `.xopp` uses. */
    fun pageSizePt(i: Int): Pair<Double, Double> = synchronized(lock) {
        sizes.getOrPut(i) { renderer.openPage(i).use { Pair(it.width.toDouble(), it.height.toDouble()) } }
    }

    /**
     * The best bitmap available *right now* for page [i] at [targetWidthPx] — never rasterises on the
     * calling thread. An exact-bucket hit is returned as-is; otherwise the nearest cached width for
     * that page is returned as a stand-in (upscaled by the renderer) and the exact size is queued in
     * the background. Null when nothing is cached yet, the page is out of range, or the cache closed.
     */
    fun request(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        synchronized(lock) {
            if (closed || i >= renderer.pageCount) return null
            val w = rasterWidth(i, targetWidthPx)
            val key = key(i, w)
            cache[key]?.let { return it }
            enqueue(key)
            return nearest(i, w)
        }
    }

    /** Queue page [i] at [targetWidthPx] for background rasterisation if it isn't cached already. */
    fun prefetch(i: Int, targetWidthPx: Int) {
        if (targetWidthPx <= 0 || i < 0) return
        synchronized(lock) {
            if (closed || i >= renderer.pageCount) return
            val key = key(i, rasterWidth(i, targetWidthPx))
            if (cache.containsKey(key)) return
            enqueue(key)
        }
    }

    /** Rasterise page [i] at [targetWidthPx] synchronously (used off the drawing path, e.g. tests). */
    fun render(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        synchronized(lock) {
            if (closed || i >= renderer.pageCount) return null
            val key = key(i, rasterWidth(i, targetWidthPx))
            return cache[key] ?: rasterise(key)
        }
    }

    // --- internals ---------------------------------------------------------------------------

    /**
     * The bucketed width page [i] may actually be rasterised at. Beyond [MAX_RASTER_WIDTH] this also
     * clamps so *one* page bitmap can never cost more than [PER_PAGE_SHARE] of the cache budget: a
     * full-page bitmap larger than the budget makes every insert evict everything else, so at high
     * zoom the visible pages would keep evicting one another and flash blank between rasterises.
     */
    private fun rasterWidth(i: Int, targetWidthPx: Int): Int {
        val (pw, ph) = pageSizePt(i)
        val aspect = if (pw > 0) ph / pw else 1.0
        // bytes = w * (w * aspect) * 4  ≤  budget * PER_PAGE_SHARE
        val byteCap = kotlin.math.sqrt(budget * PER_PAGE_SHARE / (4.0 * aspect)).toInt()
        val w = targetWidthPx.coerceAtMost(minOf(MAX_RASTER_WIDTH, byteCap.coerceAtLeast(64)))
        return bucket(w)
    }

    /** Caller holds [lock]. */
    private fun enqueue(key: Long) {
        if (!pending.add(key)) return
        worker.execute {
            val produced = synchronized(lock) {
                if (closed) null
                else {
                    pending.remove(key)
                    if (cache.containsKey(key)) null else rasterise(key)
                }
            }
            if (produced != null) onPageReady?.invoke()
        }
    }

    /** Caller holds [lock]. Rasterises and caches the page named by [key]. */
    private fun rasterise(key: Long): Bitmap? {
        val i = (key ushr 20).toInt()
        val w = (key and 0xFFFFF).toInt()
        if (i >= renderer.pageCount) return null
        val bmp = renderer.openPage(i).use { page ->
            if (page.width <= 0) return null
            val h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        put(key, bmp)
        return bmp
    }

    /** Caller holds [lock]. The cached bitmap for page [i] whose width is closest to [w], if any. */
    private fun nearest(i: Int, w: Int): Bitmap? {
        var best: Bitmap? = null
        var bestDelta = Int.MAX_VALUE
        for ((k, bmp) in cache) {
            if ((k ushr 20).toInt() != i) continue
            val delta = kotlin.math.abs((k and 0xFFFFF).toInt() - w)
            if (delta < bestDelta) { best = bmp; bestDelta = delta }
        }
        return best
    }

    /** Caller holds [lock]. */
    private fun put(key: Long, bmp: Bitmap) {
        cache.put(key, bmp)?.let { cachedBytes -= it.byteCount.toLong() }
        cachedBytes += bmp.byteCount.toLong()
        val it = cache.entries.iterator()
        while (cachedBytes > budget && cache.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            cachedBytes -= eldest.value.byteCount.toLong()
            // Not recycled: the drawing thread may still hold this bitmap for the current frame.
            it.remove()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            cache.clear()
            cachedBytes = 0
            renderer.close()
            descriptor.close()
        }
        worker.shutdown()
    }

    private companion object {
        /**
         * Byte budget for cached page bitmaps — a quarter of the heap, clamped so a short document
         * still gets a few pages and a huge heap doesn't hoard. Replaces a fixed page count: a
         * page's cost varies ~64× between thumbnail and 4k zoom, so counting pages budgets nothing.
         */
        val budget: Long = (Runtime.getRuntime().maxMemory() / 4).coerceIn(24L shl 20, 192L shl 20)

        /**
         * Ceiling on the rasterised width. At high zoom the on-screen page width grows without
         * bound, and a bitmap that wide would blow the heap; past this the background is upscaled
         * (strokes stay vector-sharp regardless). ~4k keeps a full page under ~50 MB.
         */
        const val MAX_RASTER_WIDTH = 4096

        /** The largest share of [budget] a single page bitmap may take, so several pages coexist. */
        const val PER_PAGE_SHARE = 0.25

        /** Round target widths up to 64px buckets so small zoom nudges reuse a cached bitmap. */
        fun bucket(px: Int) = ((px + 63) / 64) * 64

        fun key(i: Int, w: Int) = (i.toLong() shl 20) or w.toLong()
    }
}
