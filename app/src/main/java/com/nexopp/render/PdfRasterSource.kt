package com.nexopp.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * The PDF renderer lifecycle for [PdfPageCache]. Wraps the framework [PdfRenderer]
 * (dependency-free, API 21+ — see the dependency-free goal in `docs/architecture.md`).
 * `PdfRenderer` is not thread-safe and only one page may be open at a time, so every access is
 * serialised on [renderLock].
 *
 * **Lock ordering:** [renderLock] is never held with the cache's [lock] (from BitmapLruCache).
 * [checkSource] takes [reopenLock] first, then [renderLock] — [reopenLock] is the outermost lock.
 */
internal class PdfRasterSource(
    private val source: File,
) : Closeable {

    private var descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    private var renderer = PdfRenderer(descriptor)
    /** Identity of the bytes [renderer] was opened on, so a rewrite of [source] is detectable. */
    private var stamp = Stamp(source)
    /** Serialises [PdfRenderer], which allows one open page at a time. Never held with cache lock. */
    private val renderLock = Any()
    /** Serialises [checkSource] so two threads can't both reopen the renderer. Outermost lock. */
    private val reopenLock = Any()

    /**
     * The renderer is unusable after [close], but the count stays meaningful. Re-read when the
     * source file is replaced ([checkSource]) — the new PDF may have a different page count.
     */
    var pageCount: Int = renderer.pageCount
        private set

    /** Page [i]'s (width, height) in points (1/72"), the same unit `.xopp` uses. */
    fun pageSizePt(i: Int): Pair<Double, Double> {
        val size = synchronized(renderLock) {
            renderer.openPage(i).use { Pair(it.width.toDouble(), it.height.toDouble()) }
        }
        return size
    }

    /**
     * Re-open the renderer when [source] has been rewritten underneath us. The descriptor opened in
     * the constructor keeps pointing at the bytes it was opened on, so a document whose background
     * PDF is replaced (import, page append) would otherwise keep rasterising the *old* file — or,
     * if it was truncated in place, render white pages with no error at all. Cheap enough to call
     * on every request: two stat calls, and nothing else happens while the stamp is unchanged.
     *
     * Takes no other lock while entering [renderLock], preserving the "never held with cache lock"
     * rule; callers must clear their cache afterwards, since every bitmap in it came from the old bytes.
     */
    fun checkSource(onSourceChanged: () -> Unit) {
        if (closed) return
        synchronized(reopenLock) {
            val now = Stamp(source)
            if (now == stamp) return
            try {
                synchronized(renderLock) {
                    renderer.close()
                    descriptor.close()
                    descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(descriptor)
                    pageCount = renderer.pageCount
                    stamp = now
                }
            } catch (e: Exception) {
                // The replacement isn't a readable PDF (or vanished). Surface it as "no background"
                // rather than blank white pages: close makes every later call return null.
                android.util.Log.w("PdfRasterSource", "reopen of ${source.name} failed", e)
                close()
                return
            }
            onSourceChanged()
        }
    }

    @Volatile
    private var closed = false
        private set

    /**
     * Rasterise page [i] at [fullW] x [fullH], optionally extracting a tile cell.
     * Called without holding [renderLock] — this method takes it for the slow part.
     */
    fun rasterise(
        page: Int,
        fullW: Int,
        fullH: Int,
        tileCol: Int = -1,
        tileRow: Int = -1,
    ): Bitmap? {
        if (closed) return null
        val bmp = synchronized(renderLock) {
            if (closed) return null
            renderer.openPage(page).use { p ->
                if (p.width <= 0 || p.height <= 0) return null
                if (tileCol < 0) {
                    return@use newBitmap(fullW, fullH)?.also { p.render(it, null, null, MODE) }
                }
                val x = tileCol * TILE_PX
                val y = tileRow * TILE_PX
                val w = minOf(TILE_PX, fullW - x)
                val h = minOf(TILE_PX, fullH - y)
                if (w <= 0 || h <= 0) return null
                // Scale the page to the full grid size, then slide this cell's origin to (0,0) so the
                // tile is rendered 1:1 — no upscaling of an already-rasterised bitmap anywhere.
                val m = Matrix().apply {
                    setScale(fullW.toFloat() / p.width, fullH.toFloat() / p.height)
                    postTranslate(-x.toFloat(), -y.toFloat())
                }
                newBitmap(w, h)?.also { p.render(it, null, m, MODE) }
            }
        }
        return bmp
    }

    /** Null rather than a crash if the heap can't take another bitmap; the frame just stays coarse. */
    private fun newBitmap(w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    } catch (e: OutOfMemoryError) {
        null
    }

    override fun close() {
        if (closed) return
        closed = true
        // Under [renderLock] so an in-flight rasterise finishes before the renderer goes away.
        // Tolerant of an already-closed renderer: a failed reopen in [checkSource] closes the old
        // one before calling us, and closing twice throws.
        synchronized(renderLock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    /** The identity of a file's contents, as far as the filesystem will tell us cheaply. */
    private data class Stamp(val length: Long, val modified: Long) {
        constructor(f: File) : this(f.length(), f.lastModified())
    }

    private companion object {
        const val MODE = PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        const val TILE_PX = 512
    }
}
