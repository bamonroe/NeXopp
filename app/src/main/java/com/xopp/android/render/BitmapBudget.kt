package com.xopp.android.render

import android.app.ActivityManager
import android.content.Context

/**
 * The single memory bound every bitmap cache in the app allocates through.
 *
 * Before this existed each cache carried its own budget — [InkCache] a per-entry pixel ceiling,
 * [PdfPageCache] a heap-proportional LRU byte budget — so on a PDF-backed document the real
 * footprint was the *sum* of two independent guesses and neither cache could see the other's cost.
 * Here the bytes are counted centrally: a cache [charge]s what it just allocated, and when the
 * total goes over, the budget asks its clients to give memory back, cheapest-to-lose first.
 *
 * **Locking rule:** a client is never called back while the budget's own lock is held. Clients hold
 * their own locks when charging (see [PdfPageCache.put]), so calling back under this lock would
 * invert the lock order and deadlock. [charge] therefore computes the overage under the lock,
 * releases it, and only then asks anyone to [Client.trim].
 */
class BitmapBudget(val totalBytes: Long) {

    /** A cache that holds bitmaps counted by this budget. */
    interface Client {
        /**
         * Release at least [bytes] worth of cached bitmaps and return how many bytes were actually
         * freed. The budget subtracts what is returned, so a client must **not** also [credit] it.
         * Free the entries that cost the least to lose (off-screen, least recently used); returning
         * less than asked is fine — the budget moves on to the next client.
         */
        fun trim(bytes: Long): Long
    }

    private val lock = Any()
    private val clients = ArrayList<Client>()
    private var used = 0L

    fun register(client: Client) = synchronized(lock) { if (client !in clients) clients += client }

    fun unregister(client: Client) = synchronized(lock) { clients -= client }

    /** Bytes currently charged across every client. */
    fun used(): Long = synchronized(lock) { used }

    /** How much can still be allocated before eviction starts. Negative while over budget. */
    fun headroom(): Long = synchronized(lock) { totalBytes - used }

    /**
     * The most one cached bitmap may cost: [share] of the whole budget. A single entry larger than
     * this makes every insert evict everything else, so each cache clamps its rasters to it rather
     * than thrashing.
     */
    fun perEntryBytes(share: Double): Long = (totalBytes * share).toLong().coerceAtLeast(1L shl 20)

    /**
     * Account [bytes] just allocated by [client] and, if that puts the total over [totalBytes], ask
     * the clients to give the overage back — the other clients first, [client] itself last, since
     * the bytes it just charged are the ones it actually wants to draw with.
     */
    fun charge(client: Client, bytes: Long) {
        val (overage, order) = synchronized(lock) {
            used += bytes
            if (used <= totalBytes) return
            Pair(used - totalBytes, clients.sortedBy { it === client })
        }
        var want = overage
        for (c in order) {
            if (want <= 0) break
            val freed = c.trim(want).coerceAtLeast(0)
            want -= freed
            synchronized(lock) { used -= freed }
        }
    }

    /** Account [bytes] released by a client outside a [Client.trim] call (its own eviction, clear). */
    fun credit(bytes: Long) = synchronized(lock) { used = (used - bytes).coerceAtLeast(0) }

    companion object {
        /** Share of the heap given to bitmap caches, clamped so tiny and huge heaps both behave. */
        private const val HEAP_SHARE = 4
        private val MIN = 24L shl 20
        private val MAX = 192L shl 20

        /**
         * The process-wide budget every cache uses by default. Sized from the JVM heap until
         * [configure] replaces it with the app's real Android heap class.
         */
        @Volatile
        var shared: BitmapBudget = BitmapBudget(clamp(Runtime.getRuntime().maxMemory() / HEAP_SHARE))
            private set

        /**
         * Size [shared] from the heap this app is actually allowed ([ActivityManager.memoryClass],
         * in MB) rather than the JVM's view of it. Call once at startup, before any cache is built.
         */
        fun configure(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val heap = am.memoryClass.toLong() shl 20
            shared = BitmapBudget(clamp(heap / HEAP_SHARE))
        }

        private fun clamp(bytes: Long) = bytes.coerceIn(MIN, MAX)
    }
}
