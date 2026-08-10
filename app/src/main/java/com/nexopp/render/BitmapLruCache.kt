package com.nexopp.render

import android.graphics.Bitmap
import java.util.concurrent.Executors

/**
 * The LRU bitmap-cache core shared by [PdfPageCache] and [ImageBackgroundCache].
 *
 * Both caches answer the same question — "give me this thing rasterised at roughly this width" —
 * and had grown character-for-character copies of the machinery that makes that cheap: an
 * access-ordered map under a short-held lock, a single worker thread producing misses in the
 * background, insertion that charges the shared [BitmapBudget], and least-recently-used eviction
 * when the budget calls back. That core lives here once; the subclasses supply only what actually
 * differs — how a key is produced ([produce]), how widths are indexed ([index]/[unindex]), and
 * which entries eviction should spare ([spared]).
 *
 * **Locking:** [lock] guards the map and its bookkeeping, and is held only for fast map
 * operations. [produce] is called *without* it, so a slow rasterise never blocks a drawing thread
 * reading the cache — subclasses with their own inner locks (PdfPageCache's render lock) must keep
 * that ordering: never take [lock] inside theirs.
 */
abstract class BitmapLruCache<K : Any>(
    protected val budget: BitmapBudget,
    threadName: String,
) : BitmapBudget.Client {

    /** Guards the cache map and its bookkeeping. Held only for fast map operations. */
    protected val lock = Any()

    /** Access-ordered so eviction drops the least recently *used* entry, not the oldest. */
    protected val cache = LinkedHashMap<K, Bitmap>(16, 0.75f, true)

    /** Keys a worker is already producing, so a repeated request doesn't queue the work twice. */
    protected val pending = LinkedHashSet<K>()

    protected var cachedBytes = 0L

    /**
     * The entry [put] is inserting right now, spared by [trim] — the budget calls back into us from
     * inside that very insert, and evicting what we just produced would loop forever.
     */
    private var protectedKey: K? = null

    private val worker =
        Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }

    @Volatile
    protected var closed = false
        private set

    init {
        @Suppress("LeakingThis")
        budget.register(this)
    }

    // --- what subclasses supply --------------------------------------------------------------

    /** Produce (and cache, via [put]) the bitmap named by [key]. Called *without* [lock]. */
    protected abstract fun produce(key: K): Bitmap?

    /** Announce a newly produced entry. Called on the worker thread, outside [lock]. */
    protected abstract fun announce()

    /** Caller holds [lock]. True when the queued work for [key] is no longer worth doing. */
    protected open fun stale(key: K): Boolean = closed || cache.containsKey(key)

    /** Caller holds [lock]. Record an inserted entry in the subclass's width index. */
    protected open fun index(key: K) {}

    /** Caller holds [lock]. Forget an evicted entry's width. */
    protected open fun unindex(key: K) {}

    /** Caller holds [lock]. True while [key] should survive eviction's first pass. */
    protected open fun spared(key: K): Boolean = false

    /** Caller holds [lock]. The cache's contents changed — invalidate anything memoised from them. */
    protected open fun onCacheChanged() {}

    /** Caller holds [lock]. Clear the subclass's own derived state during [discardAll]. */
    protected open fun onDiscard() {}

    // --- the shared core ----------------------------------------------------------------------

    /** Caller holds [lock]. Queue [key] for background production if it isn't queued already. */
    protected fun enqueue(key: K) {
        if (!pending.add(key)) return
        worker.execute {
            val skip = synchronized(lock) {
                pending.remove(key)
                stale(key)
            }
            if (!skip && produce(key) != null) announce()
        }
    }

    /**
     * Inserts, then charges the shared budget — which may call straight back into [trim] to make
     * room, here or in another cache.
     *
     * **The caller must NOT hold [lock].** This takes it for the insert and releases it again
     * *before* charging, because charging reaches into every other client's [trim], which takes
     * that client's lock. Holding ours across that call inverts the order between two caches: with
     * a document mirrored into both split panes there are two [PdfPageCache]s over the same PDF,
     * and a fast scroll in each has both charging at once — the drawing thread inside cache A's
     * lock waiting on B's while A's rasteriser sits inside B's lock waiting on A's. That deadlocks
     * the main thread inside `doFrame` and the app is killed for not responding.
     *
     * [protectedKey] spares the entry we just inserted from the eviction this charge may trigger.
     */
    protected fun put(key: K, bmp: Bitmap) {
        val replaced = synchronized(lock) {
            val old = cache.put(key, bmp)?.byteCount?.toLong() ?: 0L
            cachedBytes += bmp.byteCount.toLong() - old
            index(key)
            onCacheChanged()
            protectedKey = key
            old
        }
        try {
            if (replaced > 0) budget.credit(replaced)
            budget.charge(this, bmp.byteCount.toLong())
        } finally {
            synchronized(lock) { protectedKey = null }
        }
    }

    /**
     * Give [bytes] back to the shared budget, least-recently-used first. The first pass spares the
     * entries [spared] protects (PdfPageCache's on-screen tiles); if that alone can't free enough, a
     * second pass takes them too, so a viewport too large to cache still stays memory-bounded.
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
                    if (sparePinned && spared(eldest.key)) continue
                    freed += eldest.value.byteCount.toLong()
                    it.remove()
                    unindex(eldest.key)
                }
                if (freed >= bytes) break
            }
            cachedBytes -= freed
            if (freed > 0) onCacheChanged()
        }
        return freed
    }

    /** Caller holds [lock]. Drop every cached bitmap and hand its bytes back to the budget. */
    protected fun discardAll() {
        pending.clear()
        cache.clear()
        onDiscard()
        onCacheChanged()
        budget.credit(cachedBytes)
        cachedBytes = 0
    }

    /**
     * Caller holds [lock]. The cached bitmap whose width is closest to [w] among [widths], looked up
     * through [keyOf]. A sorted lookup rather than a scan of every entry: a pinch calls it on the
     * drawing thread for each visible page each frame.
     */
    protected fun nearest(widths: java.util.TreeSet<Int>?, w: Int, keyOf: (Int) -> K): Bitmap? {
        if (widths == null) return null
        val below = widths.floor(w)
        val above = widths.ceiling(w)
        val best = when {
            below == null -> above
            above == null -> below
            w - below <= above - w -> below
            else -> above
        } ?: return null
        return cache[keyOf(best)]
    }

    /**
     * Mark the cache closed, discard its contents and stop the worker. Returns false when it was
     * already closed, so a subclass's own teardown runs exactly once.
     */
    protected fun shutdown(): Boolean {
        synchronized(lock) {
            if (closed) return false
            closed = true
            discardAll()
        }
        budget.unregister(this)
        worker.shutdown()
        return true
    }

    companion object {
        /**
         * Ceiling on the width a bitmap is rasterised or decoded at. Wider than this would blow the
         * heap for a whole page (~4k keeps a full page under ~50 MB), and for a picture background
         * there is nothing sharper to decode anyway — past it the renderer upscales.
         */
        const val MAX_RASTER_WIDTH = 4096

        /**
         * The largest share of the shared budget a single cached page raster may take, so several
         * pages coexist. An entry bigger than this makes every insert evict everything else.
         */
        const val PAGE_SHARE = 0.25

        /** Round target widths up to 64px buckets so small zoom nudges reuse a cached bitmap. */
        fun bucket(px: Int) = ((px + 63) / 64) * 64
    }
}
