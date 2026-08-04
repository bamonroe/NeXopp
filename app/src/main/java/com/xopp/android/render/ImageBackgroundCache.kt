package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executors

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
    private val budget: BitmapBudget = BitmapBudget.shared,
) : Closeable, BitmapBudget.Client {

    private data class Key(val reference: String, val width: Int)

    /** Guards the cache map and its bookkeeping. Held only for fast map operations. */
    private val lock = Any()
    /** Access-ordered so eviction drops the least recently *used* entry, not the oldest. */
    private val cache = LinkedHashMap<Key, Bitmap>(8, 0.75f, true)
    /** Cached widths per reference, so [nearest] is a sorted lookup instead of a scan. */
    private val widths = HashMap<String, java.util.TreeSet<Int>>()
    private val pending = LinkedHashSet<Key>()
    /** References whose bytes wouldn't open or decode: never queued again. */
    private val missing = HashSet<String>()
    private var cachedBytes = 0L
    /** The entry [put] is inserting right now — see [PdfPageCache]'s protected key for why. */
    private var protectedKey: Key? = null
    private val worker =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pixmap-decode").apply { isDaemon = true } }
    @Volatile private var closed = false

    /** Invoked (on the worker thread) whenever a newly decoded image enters the cache. */
    @Volatile var onImageReady: (() -> Unit)? = null

    init {
        budget.register(this)
    }

    /**
     * The best bitmap available for [reference] at [targetWidthPx]: an exact-bucket hit, else the
     * nearest cached width as a stand-in, else null while the decode runs. Either way the exact size
     * is queued in the background and announced through [onImageReady].
     */
    fun request(reference: String, targetWidthPx: Int): Bitmap? {
        if (closed || targetWidthPx <= 0 || reference.isEmpty()) return null
        val key = Key(reference, bucket(targetWidthPx.coerceAtMost(MAX_WIDTH)))
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
        val key = Key(reference, bucket(targetWidthPx.coerceAtMost(MAX_WIDTH)))
        synchronized(lock) {
            cache[key]?.let { return it }
            if (reference in missing) return null
        }
        return decode(key)
    }

    // --- internals ---------------------------------------------------------------------------

    /** Caller holds [lock]. */
    private fun enqueue(key: Key) {
        if (!pending.add(key)) return
        worker.execute {
            val stale = synchronized(lock) {
                pending.remove(key)
                closed || cache.containsKey(key) || key.reference in missing
            }
            if (!stale && decode(key) != null) onImageReady?.invoke()
        }
    }

    /**
     * Read the picture named by [key] and scale it to that width. Decoded in two passes — bounds
     * first, then with an [BitmapFactory.Options.inSampleSize] chosen from them — so a 12-megapixel
     * photo shown at tablet width never materialises at full size just to be shrunk.
     */
    private fun decode(key: Key): Bitmap? {
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
        synchronized(lock) { if (closed) return null else put(key, scaled) }
        return scaled
    }

    /** Retire a reference whose bytes won't open or decode, so no later frame queues it again. */
    private fun give(reference: String): Bitmap? {
        android.util.Log.w("ImageBackgroundCache", "cannot decode pixmap background $reference")
        synchronized(lock) { missing += reference }
        return null
    }

    /** Caller holds [lock]. The cached bitmap for [reference] whose width is closest to [w]. */
    private fun nearest(reference: String, w: Int): Bitmap? {
        val set = widths[reference] ?: return null
        val below = set.floor(w)
        val above = set.ceiling(w)
        val best = when {
            below == null -> above
            above == null -> below
            w - below <= above - w -> below
            else -> above
        } ?: return null
        return cache[Key(reference, best)]
    }

    /** Caller holds [lock]. Inserts, then charges the budget — which may call straight back to [trim]. */
    private fun put(key: Key, bmp: Bitmap) {
        val replaced = cache.put(key, bmp)?.byteCount?.toLong() ?: 0L
        cachedBytes += bmp.byteCount.toLong() - replaced
        widths.getOrPut(key.reference) { java.util.TreeSet() }.add(key.width)
        protectedKey = key
        try {
            if (replaced > 0) budget.credit(replaced)
            budget.charge(this, bmp.byteCount.toLong())
        } finally {
            protectedKey = null
        }
    }

    /**
     * Give [bytes] back, least-recently-used first. Evicted bitmaps are not recycled: the drawing
     * thread may still hold one for the frame it is painting.
     */
    override fun trim(bytes: Long): Long {
        var freed = 0L
        synchronized(lock) {
            val it = cache.entries.iterator()
            while (freed < bytes && cache.size > 1 && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == protectedKey) continue
                freed += eldest.value.byteCount.toLong()
                it.remove()
                unindex(eldest.key)
            }
            cachedBytes -= freed
        }
        return freed
    }

    /** Caller holds [lock]. */
    private fun unindex(key: Key) {
        val set = widths[key.reference] ?: return
        set.remove(key.width)
        if (set.isEmpty()) widths.remove(key.reference)
    }

    /**
     * Forget everything cached, including the [missing] blacklist — called when the document changes
     * or its pictures are resolved to new local copies, since the same reference then means
     * different bytes and a remembered failure would otherwise keep a now-resolvable page blank.
     */
    fun clear() {
        synchronized(lock) {
            pending.clear()
            cache.clear()
            widths.clear()
            missing.clear()
            budget.credit(cachedBytes)
            cachedBytes = 0
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            cache.clear()
            widths.clear()
            missing.clear()
            budget.credit(cachedBytes)
            cachedBytes = 0
        }
        budget.unregister(this)
        worker.shutdown()
    }

    companion object {
        /**
         * Ceiling on the width a background picture is held at. Past it the bitmap is upscaled by the
         * renderer rather than decoded larger — a pixmap is a photo or a screenshot, so beyond its own
         * resolution there is nothing sharper to decode anyway.
         */
        const val MAX_WIDTH = 4096

        /** Round target widths up to 64px buckets so small zoom nudges reuse a cached bitmap. */
        fun bucket(px: Int) = ((px + 63) / 64) * 64

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
