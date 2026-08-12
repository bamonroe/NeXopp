package com.nexopp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.nexopp.format.model.Page
import kotlin.math.roundToInt

/**
 * Rasterises a **whole** [Page] into a flat bitmap at a chosen DPI: the background (sheet colour and
 * ruling, or the rasterised PDF / decoded pixmap picture) with every visible layer composited on
 * top, exactly as the editor draws it. This is what raster export (PNG/JPEG/WebP) hands to the
 * encoder — one flat image per page.
 *
 * It is the full-page sibling of [PageRegionRenderer]: same [BackgroundRenderer] / [PageRenderer]
 * pair, but the [PageBox] sits at the bitmap's origin and the scale comes from a print DPI rather
 * than the viewport zoom. The caller supplies the background picture (synchronously, via
 * `PdfPageCache.render` / `ImageBackgroundCache.render`) so a PDF page is included rather than
 * skipped.
 */
object PageRasterizer {

    /** Longest side we will ever rasterise, so a high DPI on a huge page can't blow the bitmap budget. */
    const val MAX_SIDE_PX = 8192

    /** Points per inch in the `.xopp` model — the unit every page dimension is stored in. */
    private const val POINTS_PER_INCH = 72f

    /**
     * Pixel size of [widthPt] x [heightPt] (page pt) rendered at [dpi], or null for a degenerate
     * page. Each side is at least 1 px, and the scale is shrunk uniformly so neither side exceeds
     * [MAX_SIDE_PX] — the aspect ratio is preserved rather than the requested DPI.
     */
    internal fun sizeFor(widthPt: Double, heightPt: Double, dpi: Int): Pair<Int, Int>? {
        val scale = scaleFor(widthPt, heightPt, dpi) ?: return null
        // Round rather than truncate: float scale error must not shave a pixel off an exact size
        // (612 pt at 300 dpi is 2550 px, not 2549).
        val width = (widthPt * scale).roundToInt().coerceIn(1, MAX_SIDE_PX)
        val height = (heightPt * scale).roundToInt().coerceIn(1, MAX_SIDE_PX)
        return width to height
    }

    /** Px-per-pt for [dpi], capped so neither side of the page exceeds [MAX_SIDE_PX]. */
    private fun scaleFor(widthPt: Double, heightPt: Double, dpi: Int): Float? {
        if (widthPt <= 0.0 || heightPt <= 0.0 || dpi <= 0) return null
        val requested = dpi / POINTS_PER_INCH
        val capped = minOf(requested, (MAX_SIDE_PX / widthPt).toFloat(), (MAX_SIDE_PX / heightPt).toFloat())
        return if (capped > 0f) capped else null
    }

    /**
     * Draw all of [page] at [dpi]. [pageImage] is the whole-page picture for a `pdf`/`pixmap`
     * background, or null for a ruled/plain sheet; [hiddenLayers] are layer indices the editor is
     * hiding, which stay out of the export.
     *
     * Returns null for a degenerate page (non-positive extent or DPI) rather than throwing, so one
     * bad page can't take a whole export down.
     */
    fun render(
        page: Page,
        dpi: Int,
        pageImage: Bitmap? = null,
        hiddenLayers: Set<Int> = emptySet(),
    ): Bitmap? {
        val scale = scaleFor(page.width, page.height, dpi) ?: return null
        val (width, height) = sizeFor(page.width, page.height, dpi) ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val box = PageBox(
            index = 0, topPx = 0f, leftPx = 0f,
            heightPx = (page.height * scale).toFloat(), scale = scale, page = page,
        )
        BackgroundRenderer.draw(canvas, box, scrollX = 0f, scrollY = 0f, pageImage = pageImage)
        // One-shot renderer: close it so its decoded images don't sit in the shared bitmap budget
        // for the life of the process — the exported image is the only thing worth keeping.
        val elements = ElementRenderer()
        try {
            PageRenderer.drawElements(
                canvas, page, scale, 0f, 0f, StrokePainter(), elements, hiddenLayers,
            )
        } finally {
            elements.close()
        }
        return bitmap
    }
}
