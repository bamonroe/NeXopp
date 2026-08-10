package com.nexopp.render

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

private const val MAX_TILES_PER_FRAME = PdfTileGeometry.MAX_TILES_PER_FRAME
private const val PREFETCH_HEADROOM = PdfTileGeometry.PREFETCH_HEADROOM

/**
 * Rasterises the pages of a single PDF to bitmaps for use as page backgrounds. Wraps the framework
 * [PdfRenderer] (dependency-free, API 21+ — see the dependency-free goal in `docs/architecture.md`).
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
    budget: BitmapBudget = BitmapBudget.shared,
) : BitmapLruCache<PdfPageCache.Key>(budget, "pdf-raster"), Closeable {

    private val rasterSource = PdfRasterSource(source)

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

    /** Invoked (on the worker thread) whenever a newly rasterised page enters the cache. */
    @Volatile var onPageReady: (() -> Unit)? = null

    /** The page count from the underlying PDF. */
    val pageCount: Int get() = rasterSource.pageCount

    /** Page [i]'s (width, height) in points (1/72"), the same unit `.xopp` uses. */
    fun pageSizePt(i: Int): Pair<Double, Double> {
        rasterSource.checkSource { synchronized(lock) { discardAll() } }
        synchronized(lock) { sizes[i] }?.let { return it }
        val size = rasterSource.pageSizePt(i)
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
        rasterSource.checkSource { synchronized(lock) { discardAll() } }
        if (closed || i >= pageCount) return null
        val (pw, ph) = pageSizePt(i)
        val key = Key(i, PdfTileGeometry.rasterWidth(pw, ph, budget, targetWidthPx))
        synchronized(lock) {
            cache[key]?.let { return it }
            nearest(i, key.width)?.let { enqueue(key); return it }
        }
        return produce(key)
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
        rasterSource.checkSource { synchronized(lock) { discardAll() } }
        if (closed || i >= pageCount) return emptyList()
        val (pw, ph) = pageSizePt(i)
        val scale = PdfTileGeometry.tileScale(pw, ph, budget, targetWidthPx) ?: return emptyList()
        val grid = PdfTileGeometry.visibleTileGrid(pw, ph, scale, visLeft, visTop, visRight, visBottom)
            ?: return emptyList()
        synchronized(lock) {
            pin(i, grid)
            val memo = tileMemo[i]
            if (memo != null && memo.matches(grid.scale, grid.c0, grid.c1, grid.r0, grid.r1, generation)) {
                return memo.tiles
            }
        }
        val tiles = ArrayList<PdfTile>((grid.c1 - grid.c0 + 1) * (grid.r1 - grid.r0 + 1))
        var queued = 0
        synchronized(lock) {
            for (r in grid.r0..grid.r1) for (c in grid.c0..grid.c1) {
                val bmp = cache[Key(i, grid.scale, c, r)]
                if (bmp == null) {
                    if (queued < MAX_TILES_PER_FRAME) {
                        enqueue(Key(i, grid.scale, c, r))
                        queued++
                    }
                    continue
                }
                tiles += PdfTileGeometry.makeTile(bmp, c, r, grid.scale, grid.fullH)
            }
            if (budget.used() < budget.totalBytes * PdfTileGeometry.PREFETCH_HEADROOM) {
                queued += prefetchRing(i, grid, queued)
            }
            tileMemo[i] = TileMemo(grid.scale, grid.c0, grid.c1, grid.r0, grid.r1, generation, tiles)
        }
        return tiles
    }

    /**
     * Queue the ring of cells just outside the visible block, so a pan meets rasterised tiles at its
     * leading edge instead of the blurry under-layer. Caller holds [lock]; returns how many were
     * queued, never more than the remaining budget.
     */
    private fun prefetchRing(i: Int, grid: TileGrid, queued: Int): Int {
        val budgetCells = PdfTileGeometry.MAX_TILES_PER_FRAME - queued
        if (budgetCells <= 0) return 0
        var count = 0
        for (r in (grid.r0 - 1)..(grid.r1 + 1)) for (c in (grid.c0 - 1)..(grid.c1 + 1)) {
            if (r in grid.r0..grid.r1 && c in grid.c0..grid.c1) continue
            if (r < 0 || c < 0 || r >= grid.rows || c >= grid.cols) continue
            val key = Key(i, grid.scale, c, r)
            if (cache.containsKey(key)) continue
            enqueue(key)
            if (++count >= budgetCells) return count
        }
        return count
    }

    /**
     * Caller holds [lock]. Record page [i]'s visible cell block as pinned and refresh each cell's
     * LRU recency, so the tiles being drawn this frame are the last things eviction considers.
     */
    private fun pin(i: Int, grid: TileGrid) {
        val keys = HashSet<Key>((grid.c1 - grid.c0 + 1) * (grid.r1 - grid.r0 + 1))
        for (r in grid.r0..grid.r1) for (c in grid.c0..grid.c1) {
            val key = Key(i, grid.scale, c, r)
            keys += key
            cache[key]
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
        rasterSource.checkSource { synchronized(lock) { discardAll() } }
        if (closed || i >= pageCount) return
        val (pw, ph) = pageSizePt(i)
        val key = Key(i, PdfTileGeometry.rasterWidth(pw, ph, budget, targetWidthPx))
        synchronized(lock) {
            if (cache.containsKey(key)) return
            enqueue(key)
        }
    }

    /** Rasterise page [i] at [targetWidthPx] synchronously (used off the drawing path, e.g. tests). */
    fun render(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        rasterSource.checkSource { synchronized(lock) { discardAll() } }
        if (closed || i >= pageCount) return null
        val (pw, ph) = pageSizePt(i)
        val key = Key(i, PdfTileGeometry.rasterWidth(pw, ph, budget, targetWidthPx))
        synchronized(lock) { cache[key]?.let { return it } }
        return produce(key)
    }

    /** Caller holds [lock]. Drop everything derived from the bytes we were rendering. */
    override fun onDiscard() {
        pageWidths.clear()
        tileMemo.clear()
        pinnedByPage.clear()
        pinnedKeys.clear()
        sizes.clear()
    }

    /** Caller holds [lock]. Any answer memoised from the old contents is now suspect. */
    override fun onCacheChanged() {
        generation++
    }

    /** A cache entry: a whole page at a width bucket, or one cell of that width's tile grid. */
    data class Key(val page: Int, val width: Int, val col: Int = -1, val row: Int = -1) {
        val tiled get() = col >= 0
    }

    override fun announce() {
        onPageReady?.invoke()
    }

    override fun produce(key: Key): Bitmap? {
        if (closed || key.page >= pageCount) return null
        val (pw, ph) = pageSizePt(key.page)
        val fullW = key.width
        val fullH = (fullW.toLong() * ph / pw).toInt().coerceAtLeast(1)
        val bmp = if (!key.tiled) {
            rasterSource.rasterise(key.page, fullW, fullH)
        } else {
            rasterSource.rasterise(key.page, fullW, fullH, key.col, key.row)
        } ?: return null
        put(key, bmp) // takes the cache lock itself; must not be called holding it
        return bmp
    }

    /**
     * Caller holds [lock]. The cached *whole-page* bitmap for page [i] whose width is closest to
     * [w], if any. Tiles are never a stand-in — they cover only part of the page.
     */
    private fun nearest(i: Int, w: Int): Bitmap? = nearest(pageWidths[i], w) { Key(i, it) }

    /** Caller holds [lock]. Track a whole-page entry's width for [nearest]. */
    override fun index(key: Key) {
        if (key.tiled) return
        pageWidths.getOrPut(key.page) { java.util.TreeSet() }.add(key.width)
    }

    /** Caller holds [lock]. Forget an evicted whole-page entry's width. */
    override fun unindex(key: Key) {
        if (key.tiled) return
        val widths = pageWidths[key.page] ?: return
        widths.remove(key.width)
        if (widths.isEmpty()) pageWidths.remove(key.page)
    }

    /**
     * Caller holds [lock]. The tiles on screen right now survive eviction's first pass — see
     * [pinnedByPage] for what happens when they don't.
     */
    override fun spared(key: Key) = key in pinnedKeys

    /**
     * How many holders this instance has, for the shared instances handed out by [shared]. Only
     * meaningful while [registered]; a directly constructed cache has exactly one owner and is torn
     * down by the first [close].
     */
    private var refs = 1
    private var registered = false

    /** True for the refcounted instances [shared] hands out, where [close] is "release one claim". */
    val isShared: Boolean get() = synchronized(registry) { registered }

    /**
     * Release this holder's claim. A shared instance is only really torn down by its **last** holder;
     * a directly constructed one closes at once. Idempotent per holder, so the existing
     * "set a new source, close the old one" flow needs no change.
     */
    override fun close() {
        synchronized(registry) {
            if (registered) {
                if (--refs > 0) return
                if (registry[source.absolutePath] === this) registry.remove(source.absolutePath)
            }
        }
        if (!shutdown()) return
        rasterSource.close()
    }

    companion object {
        /** Live shared caches by absolute PDF path — see [shared]. Guarded by its own monitor. */
        private val registry = HashMap<String, PdfPageCache>()

        /**
         * The one cache for [file], creating it on first use and refcounting it after.
         *
         * Mirroring a PDF-backed document opens the *same* file in both panes, and a cache per pane
         * means a second `PdfRenderer` and a second set of rasterised pages against one shared
         * bitmap budget. Both panes take this instance instead, and the last one to [close] it
         * releases the file.
         */
        fun shared(file: File): PdfPageCache = synchronized(registry) {
            val key = file.absolutePath
            registry[key]?.takeIf { it.refs > 0 }?.also { it.refs++ }
                ?: PdfPageCache(file).also { it.registered = true; registry[key] = it }
        }
    }
}
