package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * One rasterised piece of a PDF page, positioned by the fraction of the page it covers so the
 * caller can place it without knowing the scale it was rendered at.
 */
data class PdfTile(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Rasterises the pages of a single PDF to bitmaps for use as page backgrounds. Wraps the framework
 * [PdfRenderer] (dependency-free, API 21+ — see the dependency-free goal in `docs/architecture.md`).
 * `PdfRenderer` is not thread-safe and only one page may be open at a time, so every access is
 * serialised on [renderLock], separately from the [lock] guarding the cache map.
 *
 * Rendering is the expensive part of scrolling a long PDF-backed document, so the cache is built to
 * keep it off the drawing frame:
 *  - entries are keyed by page, target-width bucket and (for tiles) grid cell, and evicted
 *    **least-recently-used** under a heap-proportional byte budget, so the pages you are actually
 *    scrolling through survive;
 *  - [request] returns the best whole-page bitmap already cached for that page (any width — the
 *    renderer scales it) and queues the exact size on a worker, calling [onPageReady] when the sharp
 *    version lands. It only rasterises inline when *nothing* is cached for the page, since drawing
 *    no background at all reads as a blank page;
 *  - [requestTiles] takes over past the whole-page ceiling: at high zoom only the visible cells of
 *    the page are rasterised, each at the true on-screen resolution, so text stays sharp however far
 *    you zoom while the heap stays bounded by the viewport rather than the page;
 *  - [prefetch] warms pages just outside the viewport so scrolling meets a filled cache.
 */
class PdfPageCache(
    val source: File,
    private val budget: BitmapBudget = BitmapBudget.shared,
) : Closeable, BitmapBudget.Client {

    private var descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    private var renderer = PdfRenderer(descriptor)
    /** Identity of the bytes [renderer] was opened on, so a rewrite of [source] is detectable. */
    private var stamp = Stamp(source)
    /** Guards the cache map and its bookkeeping. Held only for fast map operations. */
    private val lock = Any()
    /** Serialises [PdfRenderer], which allows one open page at a time. Never held with [lock]. */
    private val renderLock = Any()
    /** Serialises [checkSource] so two threads can't both reopen the renderer. Outermost lock. */
    private val reopenLock = Any()
    /** Access-ordered so eviction drops the least recently *used* entry, not the oldest. */
    private val cache = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    private var cachedBytes = 0L

    /**
     * Whole-page entries indexed page → cached widths, so [nearest] is a sorted lookup instead of a
     * scan of every entry. A pinch calls it on the drawing thread for each visible page each frame,
     * and the cache holds hundreds of tiles at high zoom.
     */
    private val pageWidths = HashMap<Int, java.util.TreeSet<Int>>()

    /**
     * Bumped whenever the cache's contents change, so [requestTiles] can tell a repeat of the same
     * request (reuse the memoised tile list) from one whose answer may have grown.
     */
    private var generation = 0
    /** Last tile list handed out per page, valid while grid and [generation] are unchanged. */
    private val tileMemo = HashMap<Int, TileMemo>()

    /**
     * The tile cells covering the viewport right now, per page. Eviction skips these: without it a
     * high zoom whose visible tiles plus prefetch ring outgrow [budget] evicts the very tiles being
     * drawn, so the next frame falls back to the upscaled whole-page bitmap, re-queues them, and the
     * page flickers between blurry and sharp forever. Kept flat in [pinnedKeys] so the eviction loop
     * tests membership once per candidate. Pruned by [retain] as pages leave the viewport.
     */
    private val pinnedByPage = HashMap<Int, Set<Key>>()
    private val pinnedKeys = HashSet<Key>()
    private val sizes = HashMap<Int, Pair<Double, Double>>()
    private val pending = LinkedHashSet<Key>()
    /**
     * The entry [put] is inserting right now, spared by [trim] — the budget calls back into us from
     * inside that very insert, and evicting what we just rasterised would loop forever.
     */
    private var protectedKey: Key? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-raster").apply { isDaemon = true } }
    @Volatile private var closed = false

    /** Invoked (on the worker thread) whenever a newly rasterised page enters the cache. */
    @Volatile var onPageReady: (() -> Unit)? = null

    /**
     * The renderer is unusable after [close], but the count stays meaningful. Re-read when the
     * source file is replaced ([checkSource]) — the new PDF may have a different page count.
     */
    var pageCount: Int = renderer.pageCount
        private set

    init {
        budget.register(this)
    }

    /** Page [i]'s (width, height) in points (1/72"), the same unit `.xopp` uses. */
    fun pageSizePt(i: Int): Pair<Double, Double> {
        checkSource()
        synchronized(lock) { sizes[i] }?.let { return it }
        val size = synchronized(renderLock) {
            renderer.openPage(i).use { Pair(it.width.toDouble(), it.height.toDouble()) }
        }
        synchronized(lock) { sizes[i] = size }
        return size
    }

    /**
     * The best whole-page bitmap available for page [i] at [targetWidthPx]. An exact-bucket hit is
     * returned as-is; otherwise the nearest cached width for that page is returned as a stand-in
     * (upscaled by the renderer) and the exact size is queued in the background. Only when the page
     * has nothing cached at all does this rasterise on the calling thread — drawing no background
     * reads as a blank page, which is worse than one slow frame. Null if out of range or closed.
     */
    fun request(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        checkSource()
        if (closed || i >= pageCount) return null
        val key = Key(i, rasterWidth(i, targetWidthPx))
        synchronized(lock) {
            cache[key]?.let { return it }
            nearest(i, key.width)?.let { enqueue(key); return it }
        }
        // Nothing at all is cached for this page: rasterise here rather than draw a blank page. Only
        // ever the first frame of a page — every later width is covered by the stand-in above.
        return rasterise(key)
    }

    /**
     * The rasterised tiles covering the visible part of page [i], which the caller draws over the
     * upscaled whole-page bitmap from [request]. The visible region is given as fractions of the
     * page (0..1) so this needs no screen geometry. Empty below the whole-page ceiling — there a
     * single page bitmap is already at full resolution and tiles would only duplicate it. Tiles not
     * yet rasterised are queued and omitted; they appear on a later frame via [onPageReady].
     */
    fun requestTiles(
        i: Int,
        targetWidthPx: Int,
        visLeft: Float,
        visTop: Float,
        visRight: Float,
        visBottom: Float,
    ): List<PdfTile> {
        if (targetWidthPx <= 0 || i < 0) return emptyList()
        checkSource()
        if (closed || i >= pageCount) return emptyList()
        val scale = tileScale(i, targetWidthPx) ?: return emptyList()
        val (pw, ph) = pageSizePt(i)
        if (pw <= 0) return emptyList()
        val fullH = (scale.toLong() * ph / pw).toInt().coerceAtLeast(1)
        val cols = (scale + TILE_PX - 1) / TILE_PX
        val rows = (fullH + TILE_PX - 1) / TILE_PX
        val c0 = ((visLeft * scale) / TILE_PX).toInt().coerceIn(0, cols - 1)
        val c1 = ((visRight * scale) / TILE_PX).toInt().coerceIn(0, cols - 1)
        val r0 = ((visTop * fullH) / TILE_PX).toInt().coerceIn(0, rows - 1)
        val r1 = ((visBottom * fullH) / TILE_PX).toInt().coerceIn(0, rows - 1)
        if (c1 < c0 || r1 < r0) return emptyList()
        synchronized(lock) {
            // Pin (and touch) the visible cells *before* the memo check: on a memo hit we return
            // without ever reading them out of the cache, so their LRU recency would go stale while
            // the worker keeps inserting ring tiles — and the tiles on screen would be evicted first.
            pin(i, scale, c0, c1, r0, r1)
            val memo = tileMemo[i]
            if (memo != null && memo.matches(scale, c0, c1, r0, r1, generation)) return memo.tiles
        }
        val tiles = ArrayList<PdfTile>((c1 - c0 + 1) * (r1 - r0 + 1))
        var queued = 0
        synchronized(lock) {
            for (r in r0..r1) for (c in c0..c1) {
                val bmp = cache[Key(i, scale, c, r)]
                if (bmp == null) {
                    // A wildly out-of-proportion viewport (or a fling mid-relayout) can span a whole
                    // page's worth of cells. Cap what one frame *queues* rather than dropping the
                    // request outright — the cells already rasterised are still drawn sharp.
                    if (queued < MAX_TILES_PER_FRAME) { enqueue(Key(i, scale, c, r)); queued++ }
                    continue
                }
                tiles += PdfTile(
                    bmp,
                    (c * TILE_PX).toFloat() / scale,
                    (r * TILE_PX).toFloat() / fullH,
                    minOf((c + 1) * TILE_PX, scale).toFloat() / scale,
                    minOf((r + 1) * TILE_PX, fullH).toFloat() / fullH,
                )
            }
            // Only warm the ring while there is room to hold it. Prefetching into a full cache means
            // every ring tile evicts something already earned, which is where the thrash starts.
            if (budget.used() < budget.totalBytes * PREFETCH_HEADROOM) {
                queued += prefetchRing(i, scale, cols, rows, c0, c1, r0, r1, MAX_TILES_PER_FRAME - queued)
            }
            tileMemo[i] = TileMemo(scale, c0, c1, r0, r1, generation, tiles)
        }
        return tiles
    }

    /**
     * Queue the ring of cells just outside the visible block, so a pan meets rasterised tiles at its
     * leading edge instead of the blurry under-layer. Caller holds [lock]; returns how many were
     * queued, never more than [budgetCells].
     */
    private fun prefetchRing(
        i: Int,
        scale: Int,
        cols: Int,
        rows: Int,
        c0: Int,
        c1: Int,
        r0: Int,
        r1: Int,
        budgetCells: Int,
    ): Int {
        if (budgetCells <= 0) return 0
        var queued = 0
        for (r in (r0 - 1)..(r1 + 1)) for (c in (c0 - 1)..(c1 + 1)) {
            if (r in r0..r1 && c in c0..c1) continue // the visible block, already handled
            if (r < 0 || c < 0 || r >= rows || c >= cols) continue
            val key = Key(i, scale, c, r)
            if (cache.containsKey(key)) continue
            enqueue(key)
            if (++queued >= budgetCells) return queued
        }
        return queued
    }

    /**
     * Caller holds [lock]. Record page [i]'s visible cell block as pinned and refresh each cell's
     * LRU recency, so the tiles being drawn this frame are the last things eviction considers.
     */
    private fun pin(i: Int, scale: Int, c0: Int, c1: Int, r0: Int, r1: Int) {
        val keys = HashSet<Key>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (r in r0..r1) for (c in c0..c1) {
            val key = Key(i, scale, c, r)
            keys += key
            cache[key] // access-ordered map: reading is what marks it recently used
        }
        if (pinnedByPage[i] == keys) return
        pinnedByPage[i] = keys
        rebuildPinned()
    }

    /** Caller holds [lock]. */
    private fun rebuildPinned() {
        pinnedKeys.clear()
        for (keys in pinnedByPage.values) pinnedKeys += keys
    }

    /**
     * Drop the pins of pages that have left the viewport. Call once per frame with the pages being
     * drawn (matching [PdfPageCache.requestTiles]'s page numbering); without it a scroll would leave
     * every page it passed pinned and eviction would have nothing left to reclaim.
     */
    fun retain(pages: Set<Int>) {
        synchronized(lock) {
            if (pinnedByPage.keys.retainAll(pages)) rebuildPinned()
        }
    }

    /** Queue page [i] at [targetWidthPx] for background rasterisation if it isn't cached already. */
    fun prefetch(i: Int, targetWidthPx: Int) {
        if (targetWidthPx <= 0 || i < 0) return
        checkSource()
        if (closed || i >= pageCount) return
        val key = Key(i, rasterWidth(i, targetWidthPx))
        synchronized(lock) {
            if (cache.containsKey(key)) return
            enqueue(key)
        }
    }

    /** Rasterise page [i] at [targetWidthPx] synchronously (used off the drawing path, e.g. tests). */
    fun render(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        checkSource()
        if (closed || i >= pageCount) return null
        val key = Key(i, rasterWidth(i, targetWidthPx))
        synchronized(lock) { cache[key]?.let { return it } }
        return rasterise(key)
    }

    // --- internals ---------------------------------------------------------------------------

    /** The identity of a file's contents, as far as the filesystem will tell us cheaply. */
    private data class Stamp(val length: Long, val modified: Long) {
        constructor(f: File) : this(f.length(), f.lastModified())
    }

    /**
     * Re-open the renderer when [source] has been rewritten underneath us. The descriptor opened in
     * the constructor keeps pointing at the bytes it was opened on, so a document whose background
     * PDF is replaced (import, page append) would otherwise keep rasterising the *old* file — or,
     * if it was truncated in place, render white pages with no error at all. Cheap enough to call
     * on every request: two stat calls, and nothing else happens while the stamp is unchanged.
     *
     * Takes no other lock while entering [renderLock], preserving the "never held with [lock]"
     * rule; the cache is cleared afterwards, since every bitmap in it came from the old bytes.
     */
    private fun checkSource() {
        if (closed) return
        synchronized(reopenLock) {
            val now = Stamp(source)
            if (now == stamp) return
            try {
                synchronized(renderLock) {
                    renderer.close()
                    descriptor.close()
                    descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(descriptor)
                    pageCount = renderer.pageCount
                    stamp = now
                }
            } catch (e: Exception) {
                // The replacement isn't a readable PDF (or vanished). Surface it as "no background"
                // rather than blank white pages: close makes every later call return null.
                android.util.Log.w("PdfPageCache", "reopen of ${source.name} failed", e)
                close()
                return
            }
            synchronized(lock) { discard() }
        }
    }

    /** Caller holds [lock]. Drop everything derived from the bytes we were rendering. */
    private fun discard() {
        pending.clear()
        cache.clear()
        pageWidths.clear()
        tileMemo.clear()
        pinnedByPage.clear()
        pinnedKeys.clear()
        sizes.clear()
        generation++
        budget.credit(cachedBytes)
        cachedBytes = 0
    }

    /** A cache entry: a whole page at a width bucket, or one cell of that width's tile grid. */
    private data class Key(val page: Int, val width: Int, val col: Int = -1, val row: Int = -1) {
        val tiled get() = col >= 0
    }

    /**
     * A page's last tile answer. A pan holds the same visible cell block for many frames, so
     * rebuilding the list (and a [PdfTile] per cell) each frame was pure churn on the drawing thread.
     */
    private class TileMemo(
        private val scale: Int,
        private val c0: Int,
        private val c1: Int,
        private val r0: Int,
        private val r1: Int,
        private val generation: Int,
        val tiles: List<PdfTile>,
    ) {
        fun matches(scale: Int, c0: Int, c1: Int, r0: Int, r1: Int, generation: Int) =
            this.scale == scale && this.c0 == c0 && this.c1 == c1 &&
                this.r0 == r0 && this.r1 == r1 && this.generation == generation
    }

    /**
     * The bucketed width page [i] may actually be rasterised at as a *whole page*. Beyond
     * [MAX_RASTER_WIDTH] this also clamps so one page bitmap can never cost more than
     * [PER_PAGE_SHARE] of the cache budget: a full-page bitmap larger than the budget makes every
     * insert evict everything else, so at high zoom the visible pages would keep evicting one
     * another and flash blank between rasterises. Past this ceiling sharpness comes from tiles.
     */
    private fun rasterWidth(i: Int, targetWidthPx: Int): Int {
        val (pw, ph) = pageSizePt(i)
        val aspect = if (pw > 0) ph / pw else 1.0
        // bytes = w * (w * aspect) * 4  ≤  the budget's per-entry ceiling
        val byteCap =
            kotlin.math.sqrt(budget.perEntryBytes(PER_PAGE_SHARE) / (4.0 * aspect)).toInt()
        val w = targetWidthPx.coerceAtMost(minOf(MAX_RASTER_WIDTH, byteCap.coerceAtLeast(64)))
        return bucket(w)
    }

    /**
     * The page-width the tile grid is built at, or null when tiles aren't wanted — i.e. when the
     * whole-page bitmap already covers [targetWidthPx] at full resolution. Bucketed like whole-page
     * widths so a small zoom nudge reuses the same grid instead of re-rasterising every cell.
     */
    private fun tileScale(i: Int, targetWidthPx: Int): Int? {
        val want = bucket(targetWidthPx.coerceAtMost(MAX_TILE_SCALE))
        return if (want > rasterWidth(i, targetWidthPx)) want else null
    }

    /** Caller holds [lock]. */
    private fun enqueue(key: Key) {
        if (!pending.add(key)) return
        worker.execute {
            val stale = synchronized(lock) {
                pending.remove(key)
                closed || cache.containsKey(key)
            }
            if (!stale && rasterise(key) != null) onPageReady?.invoke()
        }
    }

    /**
     * Rasterises and caches the page (or tile) named by [key]. Takes [renderLock] (not [lock]) for
     * the slow part, so a drawing thread reading the cache is never blocked behind a rasterise.
     */
    private fun rasterise(key: Key): Bitmap? {
        if (closed || key.page >= pageCount) return null
        val bmp = synchronized(renderLock) {
            if (closed) return null
            renderer.openPage(key.page).use { page ->
                if (page.width <= 0 || page.height <= 0) return null
                val fullW = key.width
                val fullH = (fullW.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                if (!key.tiled) {
                    return@use newBitmap(fullW, fullH)?.also { page.render(it, null, null, MODE) }
                }
                val x = key.col * TILE_PX
                val y = key.row * TILE_PX
                val w = minOf(TILE_PX, fullW - x)
                val h = minOf(TILE_PX, fullH - y)
                if (w <= 0 || h <= 0) return null
                // Scale the page to the full grid size, then slide this cell's origin to (0,0) so the
                // tile is rendered 1:1 — no upscaling of an already-rasterised bitmap anywhere.
                val m = Matrix().apply {
                    setScale(fullW.toFloat() / page.width, fullH.toFloat() / page.height)
                    postTranslate(-x.toFloat(), -y.toFloat())
                }
                newBitmap(w, h)?.also { page.render(it, null, m, MODE) }
            }
        } ?: return null
        synchronized(lock) { put(key, bmp) }
        return bmp
    }

    /** Null rather than a crash if the heap can't take another bitmap; the frame just stays coarse. */
    private fun newBitmap(w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Caller holds [lock]. The cached *whole-page* bitmap for page [i] whose width is closest to
     * [w], if any. Tiles are never a stand-in — they cover only part of the page.
     */
    private fun nearest(i: Int, w: Int): Bitmap? {
        val widths = pageWidths[i] ?: return null
        val below = widths.floor(w)
        val above = widths.ceiling(w)
        val best = when {
            below == null -> above
            above == null -> below
            w - below <= above - w -> below
            else -> above
        } ?: return null
        return cache[Key(i, best)]
    }

    /**
     * Caller holds [lock]. Inserts, then charges the shared budget — which may call straight back
     * into [trim] to make room, here or in another cache.
     */
    private fun put(key: Key, bmp: Bitmap) {
        val replaced = cache.put(key, bmp)?.byteCount?.toLong() ?: 0L
        cachedBytes += bmp.byteCount.toLong() - replaced
        index(key)
        generation++
        protectedKey = key
        try {
            if (replaced > 0) budget.credit(replaced)
            budget.charge(this, bmp.byteCount.toLong())
        } finally {
            protectedKey = null
        }
    }

    /**
     * Give [bytes] back to the shared budget, least-recently-used first. The first pass spares the
     * pinned (on-screen) tiles; if that alone can't free enough, a second pass takes them too, so a
     * viewport too large to cache still stays memory-bounded.
     *
     * Evicted bitmaps are not recycled: the drawing thread may still hold one for the current frame.
     */
    override fun trim(bytes: Long): Long {
        var freed = 0L
        synchronized(lock) {
            for (sparePinned in listOf(true, false)) {
                val it = cache.entries.iterator()
                while (freed < bytes && cache.size > 1 && it.hasNext()) {
                    val eldest = it.next()
                    if (eldest.key == protectedKey) continue
                    if (sparePinned && eldest.key in pinnedKeys) continue
                    freed += eldest.value.byteCount.toLong()
                    it.remove()
                    unindex(eldest.key)
                }
                if (freed >= bytes) break
            }
            cachedBytes -= freed
            if (freed > 0) generation++
        }
        return freed
    }

    /** Caller holds [lock]. Track a whole-page entry's width for [nearest]. */
    private fun index(key: Key) {
        if (key.tiled) return
        pageWidths.getOrPut(key.page) { java.util.TreeSet() }.add(key.width)
    }

    /** Caller holds [lock]. Forget an evicted whole-page entry's width. */
    private fun unindex(key: Key) {
        if (key.tiled) return
        val widths = pageWidths[key.page] ?: return
        widths.remove(key.width)
        if (widths.isEmpty()) pageWidths.remove(key.page)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            discard()
        }
        budget.unregister(this)
        // Under [renderLock] so an in-flight rasterise finishes before the renderer goes away.
        // Tolerant of an already-closed renderer: a failed reopen in [checkSource] closes the old
        // one before calling us, and closing twice throws.
        synchronized(renderLock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
        worker.shutdown()
    }

    private companion object {
        /**
         * Ceiling on the rasterised width of a *whole* page. A bitmap wider than this would blow the
         * heap; past it the whole-page bitmap is only the coarse under-layer and the sharp pixels
         * come from tiles. ~4k keeps a full page under ~50 MB.
         */
        const val MAX_RASTER_WIDTH = 4096

        /** The largest share of [budget] a single page bitmap may take, so several pages coexist. */
        const val PER_PAGE_SHARE = 0.25

        /** Edge of a tile bitmap in px: 1 MB at ARGB_8888, small enough that a miss is cheap. */
        const val TILE_PX = 512

        /**
         * Ceiling on the tile grid's page width. Tiles cost viewport-proportional memory, not
         * page-proportional, so this only exists to keep the grid arithmetic in `Int` range; it sits
         * far above any zoom the UI allows.
         */
        const val MAX_TILE_SCALE = 1 shl 19

        /**
         * Backstop on tiles *queued* from one frame (visible cells first, then the prefetch ring),
         * so a degenerate viewport can't flood the worker. Cells already cached are always drawn,
         * however many there are — this caps new work, not the answer.
         */
        const val MAX_TILES_PER_FRAME = 96

        /** Fraction of [budget] below which the prefetch ring is warmed at all. */
        const val PREFETCH_HEADROOM = 0.75

        const val MODE = PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY

        /** Round target widths up to 64px buckets so small zoom nudges reuse a cached bitmap. */
        fun bucket(px: Int) = ((px + 63) / 64) * 64
    }
}
