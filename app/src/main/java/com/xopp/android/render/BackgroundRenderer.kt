package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import com.xopp.android.format.model.Background

/**
 * Draws a page background — the base sheet colour plus its ruling (plain / lined / ruled / graph /
 * dotted) — into a [PageBox]'s rectangle. Line/dot positions come from [BackgroundGrid]; this
 * class only maps them to canvas coordinates. A `pdf` background is drawn as its rasterised page
 * ([pageImage], supplied by [PdfPageCache]); when no image is available (e.g. a `.xopp` whose PDF
 * isn't present) it, like `pixmap`, falls back to a plain sheet.
 */
object BackgroundRenderer {

    /** pt spacings, approximating desktop Xournal++ paper. */
    private const val RULE_SPACING_PT = 24.0
    private const val GRID_SPACING_PT = 14.17 // ~0.5 cm
    private const val MARGIN_PT = 72.0 // "ruled" red margin, 1 inch in

    private val fill = Paint()
    private val line = Paint().apply { color = 0xFFA9C7E8.toInt(); strokeWidth = 1f }
    private val margin = Paint().apply { color = 0xFFE79B9B.toInt(); strokeWidth = 1.5f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FB0D6.toInt(); style = Paint.Style.FILL
    }
    private val image = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(
        canvas: Canvas,
        box: PageBox,
        scrollX: Float,
        scrollY: Float,
        pageImage: Bitmap? = null,
        tiles: List<PdfTile> = emptyList(),
    ) {
        val left = box.leftPx - scrollX
        val top = box.topPx - scrollY
        val solid = box.page.background as? Background.Solid
        fill.color = solid?.color ?: AndroidColor.WHITE
        canvas.drawRect(left, top, left + box.widthPx, top + box.heightPx, fill)
        if (pageImage != null || tiles.isNotEmpty()) {
            // The whole-page bitmap is the coarse under-layer; tiles land on top at full resolution,
            // so a tile that hasn't rasterised yet shows the upscaled page rather than a hole.
            if (pageImage != null) {
                canvas.drawBitmap(pageImage, null, RectF(left, top, left + box.widthPx, top + box.heightPx), image)
            }
            for (t in tiles) {
                canvas.drawBitmap(
                    t.bitmap,
                    null,
                    RectF(
                        left + t.left * box.widthPx, top + t.top * box.heightPx,
                        left + t.right * box.widthPx, top + t.bottom * box.heightPx,
                    ),
                    image,
                )
            }
            return
        }
        when (solid?.style) {
            "lined" -> horizontals(canvas, box, left, top)
            "ruled" -> { horizontals(canvas, box, left, top); marginLine(canvas, box, left, top) }
            "graph" -> grid(canvas, box, left, top)
            "dotted" -> dots(canvas, box, left, top)
            else -> Unit // "plain", unknown, or non-solid: bare sheet
        }
    }

    private fun horizontals(canvas: Canvas, box: PageBox, left: Float, top: Float) {
        for (y in BackgroundGrid.lines(box.page.height, RULE_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            canvas.drawLine(left, py, left + box.widthPx, py, line)
        }
    }

    private fun marginLine(canvas: Canvas, box: PageBox, left: Float, top: Float) {
        val x = left + (MARGIN_PT * box.scale).toFloat()
        canvas.drawLine(x, top, x, top + box.heightPx, margin)
    }

    private fun grid(canvas: Canvas, box: PageBox, left: Float, top: Float) {
        for (y in BackgroundGrid.lines(box.page.height, GRID_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            canvas.drawLine(left, py, left + box.widthPx, py, line)
        }
        for (x in BackgroundGrid.lines(box.page.width, GRID_SPACING_PT)) {
            val px = left + (x * box.scale).toFloat()
            canvas.drawLine(px, top, px, top + box.heightPx, line)
        }
    }

    private fun dots(canvas: Canvas, box: PageBox, left: Float, top: Float) {
        val radius = (box.scale).coerceIn(1f, 2.5f)
        for (y in BackgroundGrid.lines(box.page.height, GRID_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            for (x in BackgroundGrid.lines(box.page.width, GRID_SPACING_PT)) {
                canvas.drawCircle(left + (x * box.scale).toFloat(), py, radius, dot)
            }
        }
    }
}
