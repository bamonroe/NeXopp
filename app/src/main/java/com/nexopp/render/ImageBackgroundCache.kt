package com.nexopp.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.InputStream

/**
 * Decodes the pictures behind `pixmap` page backgrounds and keeps them scaled to the size they are
 * actually drawn at — the raster counterpart of [PdfPageCache], and charged to the same shared
 * [BitmapBudget] so an image-backed document and a PDF-backed one compete for one bound rather than
 * two independent guesses.
 *
 * The cost model differs from a PDF's: a picture is one image per page, not a page-count's worth of
 * rasterisable pages, so there is no tiling and no prefetch ring here. What is kept is the part that
 * matters for a smooth frame:
 *  - entries are keyed by reference and target-width bucket and evicted **least-recently-used**;
 *  - [request] never decodes on the calling thread. A miss returns the nearest width already cached
 *    (the renderer scales it) or null, and queues the exact size on a worker, calling [onImageReady]
 *    when it lands. Decoding a phone-camera JPEG takes far longer than a frame, and a blank page for
 *    one frame beats a stalled gesture.
 *
 * [open] turns a background's `filename` into bytes — on Android a `content://` URI opened through
 * the resolver. It is called on the worker thread and may return null (the picture moved, the grant
 * expired); a reference that fails to decode is remembered in [missing] so a frame doesn't re-queue
 * it forever.
 */
class ImageBackgroundCache(
    private val open: (String) -> InputStream?,
    budget: BitmapBudget = BitmapBudget.shared,
) : BitmapLruCache<ImageBackgroundCache.Key>(budget, "pixmap-decode"), Closeable {

    data class Key(val reference: String, val width: Int)

    /** Cached widths per reference, so [nearest] is a sorted lookup instead of a scan. */
    private val widths = HashMap<String, java.util.TreeSet<Int>>()
    /** References whose bytes wouldn't open or decode: never queued again. */
    private val missing = HashSet<String>()

    /** Invoked (on the worker thread) whenever a newly decoded image enters the cache. */
    @Volatile var onImageReady: (() -> Unit)? = null

    /**
     * The best bitmap available for [reference] at [targetWidthPx]: an exact-bucket hit, else the
     * nearest cached width as a stand-in, else null while the decode runs. Either way the exact size
     * is queued in the background and announced through [onImageReady].
     */
    fun request(reference: String, targetWidthPx: Int): Bitmap? {
        if (closed || targetWidthPx <= 0 || reference.isEmpty()) return null
        val key = Key(reference, bucket(targetWidthPx.coerceAtMost(MAX_RASTER_WIDTH)))
        synchronized(lock) {
            cache[key]?.let { return it }
            if (reference in missing) return null
            enqueue(key)
            return nearest(reference, key.width)
        }
    }

    /** Decode [reference] at [targetWidthPx] synchronously (off the drawing path: export, tests). */
    fun render(reference: String, targetWidthPx: Int): Bitmap? {
        if (closed || targetWidthPx <= 0 || reference.isEmpty()) return null
        val key = Key(reference, bucket(targetWidthPx.coerceAtMost(MAX_RASTER_WIDTH)))
        synchronized(lock) {
            cache[key]?.let { return it }
            if (reference in missing) return null
        }
        return produce(key)
    }

    // --- internals ---------------------------------------------------------------------------

    override fun announce() {
        onImageReady?.invoke()
    }

    /** Caller holds [lock]. A reference retired into [missing] is never decoded again. */
    override fun stale(key: Key) = super.stale(key) || key.reference in missing

    /**
     * Read the picture named by [key] and scale it to that width. Decoded in two passes — bounds
     * first, then with an [BitmapFactory.Options.inSampleSize] chosen from them — so a 12-megapixel
     * photo shown at tablet width never materialises at full size just to be shrunk.
     */
    override fun produce(key: Key): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open(key.reference)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return give(key.reference)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, key.width)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            open(key.reference)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return give(key.reference)
        val scaled = try {
            if (decoded.width == key.width) {
                decoded
            } else {
                val h = (key.width.toLong() * decoded.height / decoded.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, key.width, h, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            // The frame just stays coarse (or blank); the reference itself is fine, so don't retire
            // it — a later, smaller target width may well fit.
            decoded.recycle()
            return null
        }
        if (closed) return null
        put(key, scaled) // takes the cache lock itself; must not be called holding it
        return scaled
    }

    /** Retire a reference whose bytes won't open or decode, so no later frame queues it again. */
    private fun give(reference: String): Bitmap? {
        android.util.Log.w("ImageBackgroundCache", "cannot decode pixmap background $reference")
        synchronized(lock) { missing += reference }
        return null
    }

    /** Caller holds [lock]. The cached bitmap for [reference] whose width is closest to [w]. */
    private fun nearest(reference: String, w: Int): Bitmap? =
        nearest(widths[reference], w) { Key(reference, it) }

    /** Caller holds [lock]. Track an entry's width for [nearest]. */
    override fun index(key: Key) {
        widths.getOrPut(key.reference) { java.util.TreeSet() }.add(key.width)
    }

    /** Caller holds [lock]. Forget an evicted entry's width. */
    override fun unindex(key: Key) {
        val set = widths[key.reference] ?: return
        set.remove(key.width)
        if (set.isEmpty()) widths.remove(key.reference)
    }

    /** Caller holds [lock]. Cleared alongside the bitmaps by [clear] and [close]. */
    override fun onDiscard() {
        widths.clear()
        missing.clear()
    }

    /**
     * Forget everything cached, including the [missing] blacklist — called when the document changes
     * or its pictures are resolved to new local copies, since the same reference then means
     * different bytes and a remembered failure would otherwise keep a now-resolvable page blank.
     */
    fun clear() {
        synchronized(lock) { discardAll() }
    }

    override fun close() {
        shutdown()
    }

    companion object {
        /**
         * The power-of-two subsample that gets [sourceWidth] closest to [targetWidth] **without**
         * dropping below it — decoding under the target and upscaling would show as a blurry page,
         * so this always leaves at least the wanted pixels for the final exact scale.
         */
        fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
            if (sourceWidth <= 0 || targetWidth <= 0) return 1
            var sample = 1
            while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
            return sample
        }
    }
}
