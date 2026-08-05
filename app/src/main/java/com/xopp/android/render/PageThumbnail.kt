package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.xopp.android.format.model.Page

/**
 * Rasterises a single [Page] into a small preview bitmap — the sheet background plus every visible
 * element, drawn through the same [BackgroundRenderer] / [PageRenderer] the editor and the PDF
 * export use, so a preview looks like the page it stands for.
 *
 * A `pdf` or `pixmap` background is drawn as its plain sheet colour only: the preview is deliberately
 * cheap and synchronous (it runs inside a tap that opens the tab overview), and the caches those
 * backgrounds come from are asynchronous and owned by a live canvas. Ink still lands on top, which is
 * what tells two tabs apart at thumbnail size.
 */
object PageThumbnail {

    /** Widest preview we will ever rasterise, so a huge grid can't blow the bitmap budget. */
    private const val MAX_WIDTH_PX = 400

    /**
     * Draw [page] scaled to [widthPx] wide (height follows the page's aspect ratio). Returns null for
     * a degenerate page rather than throwing, so a malformed document can't take the overview down.
     */
    fun render(page: Page, widthPx: Int): Bitmap? {
        val width = widthPx.coerceIn(1, MAX_WIDTH_PX)
        if (page.width <= 0.0 || page.height <= 0.0) return null
        val scale = (width / page.width).toFloat()
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val box = PageBox(index = 0, topPx = 0f, leftPx = 0f, heightPx = height.toFloat(), scale = scale, page = page)
        BackgroundRenderer.draw(canvas, box, scrollX = 0f, scrollY = 0f)
        PageRenderer.drawElements(canvas, page, scale, offsetX = 0f, offsetY = 0f, StrokePainter(), ElementRenderer())
        return bitmap
    }
}
