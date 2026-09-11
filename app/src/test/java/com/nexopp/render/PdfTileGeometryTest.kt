package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile grid's engagement threshold. Tiling exists for deep zoom; switching it on during normal
 * reading is what thrashed the bitmap budget on wide tablets (every visible page's tile working set
 * outgrew the budget and re-rasterised every frame), so these pin exactly when tiles turn on.
 */
class PdfTileGeometryTest {

    // A4 portrait in points.
    private val pw = 595.0
    private val ph = 842.0

    /** The smallest budget the clamp allows — the worst case for the old budget-keyed threshold. */
    private val budget = BitmapBudget(32L shl 20)

    @Test
    fun `no tiles at 100 percent zoom on a wide tablet`() {
        assertNull(PdfTileGeometry.tileScale(pw, ph, budget, 2560))
    }

    @Test
    fun `no tiles up to the whole-page raster ceiling`() {
        assertNull(PdfTileGeometry.tileScale(pw, ph, budget, BitmapLruCache.MAX_RASTER_WIDTH))
    }

    @Test
    fun `tiles engage past the whole-page raster ceiling`() {
        val scale = PdfTileGeometry.tileScale(pw, ph, budget, 8192)
        assertNotNull(scale)
        assertEquals(BitmapLruCache.bucket(8192), scale)
    }

    @Test
    fun `whole-page raster width is bucketed and bounded by target and ceiling`() {
        val w = PdfTileGeometry.rasterWidth(pw, ph, budget, 1600)
        assertTrue(w <= BitmapLruCache.bucket(1600))
        assertTrue(w <= BitmapLruCache.MAX_RASTER_WIDTH)
    }
}
