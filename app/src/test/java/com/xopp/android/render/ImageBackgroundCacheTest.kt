package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure sizing arithmetic of [ImageBackgroundCache]; the decode itself needs a real
 * `BitmapFactory` and so belongs to the on-device pass.
 */
class ImageBackgroundCacheTest {

    @Test
    fun `sample size never drops below the target width`() {
        // 4000px source shown at 1000px: /4 lands exactly on the target, /8 would be too small.
        assertEquals(4, ImageBackgroundCache.sampleSize(4000, 1000))
        assertEquals(2, ImageBackgroundCache.sampleSize(4000, 1001))
    }

    @Test
    fun `a source no larger than the target is decoded whole`() {
        assertEquals(1, ImageBackgroundCache.sampleSize(800, 1000))
        assertEquals(1, ImageBackgroundCache.sampleSize(1000, 1000))
    }

    @Test
    fun `degenerate sizes fall back to a full decode`() {
        assertEquals(1, ImageBackgroundCache.sampleSize(0, 1000))
        assertEquals(1, ImageBackgroundCache.sampleSize(1000, 0))
    }

    @Test
    fun `widths are bucketed so a zoom nudge reuses a cached decode`() {
        assertEquals(64, ImageBackgroundCache.bucket(1))
        assertEquals(1024, ImageBackgroundCache.bucket(1024))
        assertEquals(1088, ImageBackgroundCache.bucket(1025))
    }
}
