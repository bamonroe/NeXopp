package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.xopp.android.format.model.Page

/**
 * Rasterises a **rectangular region** of a [Page] into a flat bitmap: the background (sheet colour
 * and ruling, or the rasterised PDF / decoded pixmap picture) with every visible layer composited on
 * top, exactly as the editor draws it. This is what the background-select tool copies — one flat
 * image of everything under the marquee, rather than the elements that happen to intersect it.
 *
 * It is the region-addressed sibling of [PageThumbnail]: same [BackgroundRenderer] / [PageRenderer]
 * pair, but the [PageBox] is offset so the region's top-left lands at the bitmap's origin, and the
 * caller supplies the background picture (synchronously, via `PdfPageCache.render` /
 * `ImageBackgroundCache.render`) so a PDF page is included rather than skipped.
 */
object PageRegionRenderer {

    /** Longest side we will ever rasterise, so a marquee at deep zoom can't blow the bitmap budget. */
    const val MAX_SIDE_PX = 2048

    /**
     * Draw the part of [page] inside [region] (page-local pt) at [scale] px/pt. [pageImage] is the
     * whole-page picture for a `pdf`/`pixmap` background, or null for a ruled/plain sheet;
     * [hiddenLayers] are layer indices the editor is hiding, which stay out of the copy.
     *
     * The scale is capped so neither side exceeds [MAX_SIDE_PX]. Returns null for a degenerate
     * region rather than throwing, so a stray tap can't take the canvas down.
     */
    fun render(
        page: Page,
        region: Bounds,
        scale: Float,
        pageImage: Bitmap? = null,
        hiddenLayers: Set<Int> = emptySet(),
    ): Bitmap? {
        val wPt = region.right - region.left
        val hPt = region.bottom - region.top
        if (wPt <= 0.0 || hPt <= 0.0 || scale <= 0f) return null
        val capped = minOf(scale, (MAX_SIDE_PX / wPt).toFloat(), (MAX_SIDE_PX / hPt).toFloat())
        if (capped <= 0f) return null
        val width = (wPt * capped).toInt().coerceAtLeast(1)
        val height = (hPt * capped).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Slide the whole page so the region's corner sits at (0,0); the canvas clips the rest away.
        val offsetX = (-region.left * capped).toFloat()
        val offsetY = (-region.top * capped).toFloat()
        val box = PageBox(
            index = 0, topPx = offsetY, leftPx = offsetX,
            heightPx = (page.height * capped).toFloat(), scale = capped, page = page,
        )
        BackgroundRenderer.draw(canvas, box, scrollX = 0f, scrollY = 0f, pageImage = pageImage)
        // One-shot renderer: close it so its decoded images don't sit in the shared bitmap budget
        // for the life of the process — the flattened copy is the only thing worth keeping.
        val elements = ElementRenderer()
        try {
            PageRenderer.drawElements(
                canvas, page, capped, offsetX, offsetY, StrokePainter(), elements, hiddenLayers,
            )
        } finally {
            elements.close()
        }
        return bitmap
    }
}
