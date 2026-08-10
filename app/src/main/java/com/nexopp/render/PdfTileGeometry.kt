package com.nexopp.render

import android.graphics.Bitmap

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
 * A page's last tile answer. A pan holds the same visible cell block for many frames, so
 * rebuilding the list (and a [PdfTile] per cell) each frame was pure churn on the drawing thread.
 */
internal class TileMemo(
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
 * Tile geometry calculations for PDF page rasterisation. Given a page size and target width,
 * computes the tile grid dimensions and the visible cell range for a viewport.
 */
internal object PdfTileGeometry {

    /**
     * The bucketed width a page may be rasterised at as a *whole page*. Beyond
     * [BitmapLruCache.MAX_RASTER_WIDTH] this also clamps so one page bitmap can never cost more than
     * [PAGE_SHARE] of the cache budget: a full-page bitmap larger than the budget makes every
     * insert evict everything else, so at high zoom the visible pages would keep evicting one
     * another and flash blank between rasterises. Past this ceiling sharpness comes from tiles.
     */
    fun rasterWidth(pageWidthPt: Double, pageHeightPt: Double, budget: BitmapBudget, targetWidthPx: Int): Int {
        val aspect = if (pageWidthPt > 0) pageHeightPt / pageWidthPt else 1.0
        // bytes = w * (w * aspect) * 4  ≤  the budget's per-entry ceiling
        val byteCap =
            kotlin.math.sqrt(budget.perEntryBytes(PAGE_SHARE) / (4.0 * aspect)).toInt()
        val w = targetWidthPx.coerceAtMost(minOf(BitmapLruCache.MAX_RASTER_WIDTH, byteCap.coerceAtLeast(64)))
        return BitmapLruCache.bucket(w)
    }

    /**
     * The page-width the tile grid is built at, or null when tiles aren't wanted — i.e. when the
     * whole-page bitmap already covers [targetWidthPx] at full resolution. Bucketed like whole-page
     * widths so a small zoom nudge reuses the same grid instead of re-rasterising every cell.
     */
    fun tileScale(pageWidthPt: Double, pageHeightPt: Double, budget: BitmapBudget, targetWidthPx: Int): Int? {
        val want = BitmapLruCache.bucket(targetWidthPx.coerceAtMost(MAX_TILE_SCALE))
        val wholePageWidth = rasterWidth(pageWidthPt, pageHeightPt, budget, targetWidthPx)
        return if (want > wholePageWidth) want else null
    }

    /**
     * Compute the tile grid and visible cell range for page [i] at [scale].
     * Returns (cols, rows, c0, c1, r0, r1) or null if the scale is invalid.
     */
    fun visibleTileGrid(
        pageWidthPt: Double,
        pageHeightPt: Double,
        scale: Int,
        visLeft: Float,
        visTop: Float,
        visRight: Float,
        visBottom: Float,
    ): TileGrid? {
        if (pageWidthPt <= 0) return null
        val fullH = (scale.toLong() * pageHeightPt / pageWidthPt).toInt().coerceAtLeast(1)
        val cols = (scale + TILE_PX - 1) / TILE_PX
        val rows = (fullH + TILE_PX - 1) / TILE_PX
        val c0 = ((visLeft * scale) / TILE_PX).toInt().coerceIn(0, cols - 1)
        val c1 = ((visRight * scale) / TILE_PX).toInt().coerceIn(0, cols - 1)
        val r0 = ((visTop * fullH) / TILE_PX).toInt().coerceIn(0, rows - 1)
        val r1 = ((visBottom * fullH) / TILE_PX).toInt().coerceIn(0, rows - 1)
        if (c1 < c0 || r1 < r0) return null
        return TileGrid(scale, fullH, cols, rows, c0, c1, r0, r1)
    }

    /**
     * Build a [PdfTile] for the cell at (col, row) from a cached bitmap.
     */
    fun makeTile(bmp: Bitmap, col: Int, row: Int, scale: Int, fullH: Int): PdfTile =
        PdfTile(
            bmp,
            (col * TILE_PX).toFloat() / scale,
            (row * TILE_PX).toFloat() / fullH,
            minOf((col + 1) * TILE_PX, scale).toFloat() / scale,
            minOf((row + 1) * TILE_PX, fullH).toFloat() / fullH,
        )

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

    /**
     * The largest share of the shared budget a single cached page raster may take, so several
     * pages coexist. An entry bigger than this makes every insert evict everything else.
     */
    const val PAGE_SHARE = 0.25
}

/**
 * The computed tile grid for a visible viewport region.
 */
internal data class TileGrid(
    val scale: Int,
    val fullH: Int,
    val cols: Int,
    val rows: Int,
    val c0: Int,
    val c1: Int,
    val r0: Int,
    val r1: Int,
)
