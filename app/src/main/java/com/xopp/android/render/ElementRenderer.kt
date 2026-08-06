package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.xopp.android.format.FontDescription
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import java.util.IdentityHashMap

/**
 * Draws the non-stroke [Element]s — text boxes, images, and LaTeX images — onto the page canvas.
 * All element geometry is page-local pt; the caller passes the page [scale] (px per pt) and the
 * page's top-left offset (`offsetX`, `offsetY`, px) so elements land in the same space as the strokes.
 *
 * Decoded image bitmaps are cached by element identity so a large PNG isn't re-decoded every frame.
 * Those bitmaps are charged to the shared [BitmapBudget] and handed back least-recently-used first
 * ([trim]), so a document with many embedded pictures can't grow the heap without bound — the same
 * arrangement [InkCache] and [ImageBackgroundCache] live under. Call [close] when the renderer is
 * done (surface teardown, one-shot thumbnail) to recycle what it holds and leave the budget.
 *
 * A `<teximage>` carries only its LaTeX source in our model (no rendered glyphs), so the source is
 * parsed once (cached by element identity, like the bitmap cache) into a [LatexNode] tree and drawn
 * as real math by [LatexRenderer]. Any parse/draw failure falls back to the raw source text so a
 * malformed formula can never crash a frame.
 */
class ElementRenderer(
    private val budget: BitmapBudget = BitmapBudget.shared,
) : BitmapBudget.Client {

    /** Guards [bitmapCache]: [trim] can arrive from another cache's worker thread. */
    private val lock = Any()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Access order, so the iterator hands [trim] the least recently drawn image first. */
    private val bitmapCache = LinkedHashMap<IdentityKey, Bitmap?>(16, 0.75f, true)
    private val latexRenderer = LatexRenderer()
    private val texCache = IdentityHashMap<TexImageElement, LatexNode?>()

    init {
        budget.register(this)
    }

    /**
     * A cache key that is the *element itself* by reference: [ImageElement.equals] compares whole
     * byte arrays, so hashing by content would cost megabytes of work per image per frame.
     */
    private class IdentityKey(val element: ImageElement) {
        override fun hashCode(): Int = System.identityHashCode(element)
        override fun equals(other: Any?): Boolean =
            other is IdentityKey && other.element === element
    }

    /** Recycle every decoded bitmap and leave the shared budget. The renderer is unusable after. */
    fun close() {
        var freed = 0L
        synchronized(lock) {
            for (bmp in bitmapCache.values) bmp?.let { freed += it.byteCount.toLong(); it.recycle() }
            bitmapCache.clear()
        }
        texCache.clear()
        budget.unregister(this)
        if (freed > 0) budget.credit(freed)
    }

    /**
     * Give [bytes] back, least-recently-drawn image first. As in [InkCache.trim], bitmaps dropped
     * here are **not** recycled: this can run on another cache's worker thread while the drawing
     * thread still holds one for the current frame.
     */
    override fun trim(bytes: Long): Long {
        var freed = 0L
        synchronized(lock) {
            val it = bitmapCache.entries.iterator()
            while (freed < bytes && it.hasNext()) {
                freed += it.next().value?.byteCount?.toLong() ?: 0L
                it.remove()
            }
        }
        return freed
    }

    fun draw(canvas: Canvas, element: Element, scale: Float, offsetX: Float, offsetY: Float) {
        when (element) {
            is TextElement -> drawText(canvas, element, scale, offsetX, offsetY)
            is ImageElement -> drawImage(canvas, element, scale, offsetX, offsetY)
            is TexImageElement -> drawTex(canvas, element, scale, offsetX, offsetY)
            else -> Unit // strokes are drawn by DrawingSurfaceView
        }
    }

    private fun drawText(canvas: Canvas, t: TextElement, scale: Float, offsetX: Float, offsetY: Float) {
        textPaint.color = t.color
        textPaint.textSize = (t.size * scale).toFloat()
        textPaint.typeface = typefaceFor(t.font)
        val fm = textPaint.fontMetrics
        val topPx = offsetY + (t.y * scale).toFloat()
        val xPx = offsetX + (t.x * scale).toFloat()
        val lineHeight = fm.descent - fm.ascent
        val lines = TextBlock.lines(t.content)
        val baselines = TextBlock.baselines(lines.size, topPx, fm.ascent, lineHeight)
        for (i in lines.indices) canvas.drawText(lines[i], xPx, baselines[i], textPaint)
    }

    /**
     * Resolve a `.xopp` font description into an Android [Typeface]. The Pango-style bold/italic
     * tokens become a [Typeface] style, and the desktop family names Xournal++ uses map onto the
     * generic Android families (unknown families fall back to Android's own resolution).
     */
    private fun typefaceFor(font: String): Typeface {
        val fd = FontDescription.parse(font)
        val style = when {
            fd.bold && fd.italic -> Typeface.BOLD_ITALIC
            fd.bold -> Typeface.BOLD
            fd.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val androidFamily = when (fd.family.lowercase()) {
            "sans", "sans-serif" -> "sans-serif"
            "serif" -> "serif"
            "monospace", "mono" -> "monospace"
            else -> fd.family
        }
        return Typeface.create(androidFamily, style)
    }

    private fun drawImage(canvas: Canvas, img: ImageElement, scale: Float, offsetX: Float, offsetY: Float) {
        val key = IdentityKey(img)
        val cached = synchronized(lock) { if (bitmapCache.containsKey(key)) bitmapCache[key] else MISS }
        val bmp = if (cached !== MISS) {
            cached as Bitmap?
        } else {
            val fresh = try {
                BitmapFactory.decodeByteArray(img.data, 0, img.data.size)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "out of memory decoding a ${img.data.size}-byte embedded image", e)
                null
            }
            synchronized(lock) { bitmapCache[key] = fresh }
            // Charged after the insert, so an over-budget total can evict this very entry if it must.
            fresh?.let { budget.charge(this, it.byteCount.toLong()) }
            fresh
        } ?: return
        canvas.drawBitmap(bmp, null, rect(img.left, img.top, img.right, img.bottom, scale, offsetX, offsetY), bitmapPaint)
    }

    private fun drawTex(canvas: Canvas, tex: TexImageElement, scale: Float, offsetX: Float, offsetY: Float) {
        if (tex.latex.isBlank()) return
        val dst = rect(tex.left, tex.top, tex.right, tex.bottom, scale, offsetX, offsetY)
        // Parse the LaTeX source once and cache the tree by element identity (like the bitmap cache).
        val node = try {
            texCache.getOrPut(tex) { LatexParser.parse(tex.latex) }
        } catch (e: Throwable) {
            Log.w(TAG, "cannot parse LaTeX \"${tex.latex}\" — falling back to raw source", e)
            null
        }
        if (node != null) {
            try {
                // Cap the font so a single glyph never balloons past the box; fit does the rest.
                latexRenderer.draw(canvas, node, dst, tex.color, dst.height())
                return
            } catch (e: Throwable) {
                Log.w(TAG, "cannot draw LaTeX \"${tex.latex}\" — falling back to raw source", e)
                // fall through to the raw-source fallback below
            }
        }
        drawTexFallback(canvas, tex, dst, scale)
    }

    /** Last-resort rendering: draw the raw LaTeX source as monospace text, clipped to the box. */
    private fun drawTexFallback(canvas: Canvas, tex: TexImageElement, dst: RectF, scale: Float) {
        textPaint.color = tex.color
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = (dst.height() * 0.5f).coerceIn(8f, 14f * scale)
        val fm = textPaint.fontMetrics
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawText(tex.latex, dst.left + 2f, dst.top - fm.ascent + 2f, textPaint)
        canvas.restore()
    }

    private fun rect(left: Double, top: Double, right: Double, bottom: Double, scale: Float, offsetX: Float, offsetY: Float) =
        RectF(
            offsetX + (left * scale).toFloat(),
            offsetY + (top * scale).toFloat(),
            offsetX + (right * scale).toFloat(),
            offsetY + (bottom * scale).toFloat(),
        )

    private companion object {
        const val TAG = "ElementRenderer"

        /** Distinguishes "not cached" from a cached null (an image whose bytes won't decode). */
        val MISS = Any()
    }
}
