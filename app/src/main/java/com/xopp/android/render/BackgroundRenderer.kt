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

    // Reused across blits: [draw] runs on the drawing thread every frame, and a fresh RectF per
    // tile per frame was pure churn for the collector.
    private val dst = RectF()

    fun draw(
        canvas: Canvas,
        box: PageBox,
        scrollX: Float,
        scrollY: Float,
        pageImage: Bitmap? = null,
        tiles: List<PdfTile> = emptyList(),
        viewWidthPx: Float = 0f,
        viewHeightPx: Float = 0f,
    ) {
        val left = box.leftPx - scrollX
        val top = box.topPx - scrollY
        val solid = box.page.background as? Background.Solid
        fill.color = solid?.color ?: AndroidColor.WHITE
        canvas.drawRect(left, top, left + box.widthPx, top + box.heightPx, fill)
        if (pageImage != null || tiles.isNotEmpty()) {
            // The whole-page bitmap is the coarse under-layer; tiles land on top at full resolution,
            // so a tile that hasn't rasterised yet shows the upscaled page rather than a hole.
            // …unless the tiles already cover every visible pixel, in which case the blit would
            // rasterise the whole (possibly many-screens-wide) page only to be painted over.
            if (pageImage != null && !tilesCover(tiles, box, left, top, viewWidthPx, viewHeightPx)) {
                dst.set(left, top, left + box.widthPx, top + box.heightPx)
                canvas.drawBitmap(pageImage, null, dst, image)
            }
            for (t in tiles) {
                dst.set(
                    left + t.left * box.widthPx, top + t.top * box.heightPx,
                    left + t.right * box.widthPx, top + t.bottom * box.heightPx,
                )
                canvas.drawBitmap(t.bitmap, null, dst, image)
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

    /**
     * True if [tiles] leave no hole over the visible part of the page. Tiles are non-overlapping grid
     * cells, so a gap-free cover is exactly "their union rectangle contains the visible rectangle and
     * their areas add up to that union" — no per-cell bookkeeping needed. Returns false when the view
     * size isn't known (the PDF exporter, tests), preserving the coarse under-layer there.
     */
    private fun tilesCover(
        tiles: List<PdfTile>,
        box: PageBox,
        left: Float,
        top: Float,
        viewWidthPx: Float,
        viewHeightPx: Float,
    ): Boolean {
        if (tiles.isEmpty() || viewWidthPx <= 0f || viewHeightPx <= 0f) return false
        if (box.widthPx <= 0f || box.heightPx <= 0f) return false
        // The visible slice of the page, as 0..1 fractions like PdfTile's own coordinates.
        val vl = ((0f - left) / box.widthPx).coerceIn(0f, 1f)
        val vt = ((0f - top) / box.heightPx).coerceIn(0f, 1f)
        val vr = ((viewWidthPx - left) / box.widthPx).coerceIn(0f, 1f)
        val vb = ((viewHeightPx - top) / box.heightPx).coerceIn(0f, 1f)
        if (vr <= vl || vb <= vt) return true // nothing of this page is on screen
        var ul = Float.MAX_VALUE; var ut = Float.MAX_VALUE
        var ur = -Float.MAX_VALUE; var ub = -Float.MAX_VALUE
        var area = 0f
        for (t in tiles) {
            ul = minOf(ul, t.left); ut = minOf(ut, t.top)
            ur = maxOf(ur, t.right); ub = maxOf(ub, t.bottom)
            area += (t.right - t.left) * (t.bottom - t.top)
        }
        if (ul > vl || ut > vt || ur < vr || ub < vb) return false
        val unionArea = (ur - ul) * (ub - ut)
        return area >= unionArea - 1e-4f
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
