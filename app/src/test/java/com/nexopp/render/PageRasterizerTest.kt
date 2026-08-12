package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure size arithmetic of [PageRasterizer]; the drawing itself needs Android graphics
 * and is exercised on the emulator, not here.
 */
class PageRasterizerTest {

    @Test fun seventyTwoDpiIsOnePixelPerPoint() {
        assertEquals(612 to 792, PageRasterizer.sizeFor(612.0, 792.0, 72))
    }

    @Test fun threeHundredDpiScalesExactly() {
        // 612 pt / 72 * 300 = 2550 px; 792 pt likewise 3300 px.
        assertEquals(2550 to 3300, PageRasterizer.sizeFor(612.0, 792.0, 300))
    }

    @Test fun longestSideIsCappedWithAspectPreserved() {
        // 10000 x 5000 pt at 600 dpi would be 83333 px wide; the cap shrinks both sides uniformly.
        val size = PageRasterizer.sizeFor(10_000.0, 5_000.0, 600)
        assertEquals(PageRasterizer.MAX_SIDE_PX to PageRasterizer.MAX_SIDE_PX / 2, size)
    }

    @Test fun tinyPageStillYieldsAtLeastOnePixel() {
        assertEquals(1 to 1, PageRasterizer.sizeFor(0.1, 0.1, 72))
    }

    @Test fun degenerateInputsReturnNull() {
        assertNull(PageRasterizer.sizeFor(0.0, 792.0, 300))
        assertNull(PageRasterizer.sizeFor(612.0, -1.0, 300))
        assertNull(PageRasterizer.sizeFor(612.0, 792.0, 0))
    }
}
