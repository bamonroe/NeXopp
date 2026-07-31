package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Rasterises the pages of a single PDF to bitmaps for use as page backgrounds. Wraps the framework
 * [PdfRenderer] (dependency-free, API 21+ — see the dependency-free goal in `docs/architecture.md`).
 * `PdfRenderer` is not thread-safe and only one page may be open at a time, so every access is
 * serialised on [lock]. Rendered pages are cached by (page, target-width bucket) so scrolling and
 * redraws don't re-rasterise; the cache is bounded and recycles evicted bitmaps.
 */
class PdfPageCache(file: File) : Closeable {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Any()
    private val cache = LinkedHashMap<Long, Bitmap>()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Page [i]'s (width, height) in points (1/72"), the same unit `.xopp` uses. */
    fun pageSizePt(i: Int): Pair<Double, Double> = synchronized(lock) {
        renderer.openPage(i).use { Pair(it.width.toDouble(), it.height.toDouble()) }
    }

    /**
     * Page [i] rasterised to a bitmap [targetWidthPx] wide (height follows the page aspect ratio),
     * white-filled so transparent PDFs read as paper. Returns a cached bitmap when one exists for a
     * nearby width; null if the page is out of range or the cache is closed.
     */
    fun render(i: Int, targetWidthPx: Int): Bitmap? {
        if (targetWidthPx <= 0 || i < 0) return null
        synchronized(lock) {
            if (closed || i >= renderer.pageCount) return null
            val w = bucket(targetWidthPx)
            val key = (i.toLong() shl 20) or w.toLong()
            cache[key]?.let { return it }
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
    }

    private fun put(key: Long, bmp: Bitmap) {
        cache[key] = bmp
        while (cache.size > MAX_CACHED) {
            val eldest = cache.keys.iterator().next()
            cache.remove(eldest)?.recycle()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        cache.values.forEach { it.recycle() }
        cache.clear()
        renderer.close()
        descriptor.close()
    }

    private companion object {
        const val MAX_CACHED = 8
        /** Round target widths up to 64px buckets so small zoom nudges reuse a cached bitmap. */
        fun bucket(px: Int) = ((px + 63) / 64) * 64
    }
}
