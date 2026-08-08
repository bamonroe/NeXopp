package com.xopp.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xopp.android.render.BitmapBudget
import com.xopp.android.render.BitmapLruCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two caches sharing one [BitmapBudget] must never deadlock against each other.
 *
 * This is the split-view mirror hang: mirroring a PDF-backed document gives each pane its own
 * `PdfPageCache` over the same file, both charging one budget. [BitmapBudget.charge] reclaims from
 * the *other* client first, so when [BitmapLruCache.put] charged while holding its own lock, two
 * simultaneous inserts took the two caches' locks in opposite orders — the drawing thread inside
 * cache A waiting on B while A's rasteriser sat inside B waiting on A. The main thread hung inside
 * `doFrame` and the app was killed with "Input dispatching timed out". Scrolling both panes fast
 * reproduced it in seconds on a Tab Ultra.
 *
 * Needs real [Bitmap]s (`byteCount` drives the accounting), so it runs on device rather than in the
 * JVM unit pass.
 */
@RunWith(AndroidJUnit4::class)
class BitmapCacheDeadlockTest {

    /** A minimal cache over the real [BitmapLruCache] core; [produce] is what the workers race on. */
    private class TestCache(budget: BitmapBudget, name: String) :
        BitmapLruCache<Int>(budget, name) {

        override fun announce() {}

        override fun produce(key: Int): Bitmap? {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            put(key, bmp) // must not be called holding the cache lock
            return bmp
        }

        /** Insert [count] entries, as a pane's rasteriser does while a fling scrolls past pages. */
        fun churn(count: Int) {
            for (i in 0 until count) produce(i)
        }
    }

    @Test
    fun twoCachesOnOneBudgetDoNotDeadlock() {
        // Small enough that every insert goes over and calls into the other cache's trim.
        val budget = BitmapBudget(256L shl 10)
        val a = TestCache(budget, "cache-a")
        val b = TestCache(budget, "cache-b")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        for (cache in listOf(a, b)) {
            Thread {
                start.await()
                cache.churn(400)
                done.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(
            "two caches charging one budget deadlocked — see BitmapLruCache.put",
            done.await(30, TimeUnit.SECONDS),
        )
    }
}
