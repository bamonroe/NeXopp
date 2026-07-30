package com.xopp.android.render

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import com.xopp.android.format.model.Background

/**
 * Draws a page background — the base sheet colour plus its ruling (plain / lined / ruled / graph /
 * dotted) — into a [PageBox]'s rectangle. Line/dot positions come from [BackgroundGrid]; this
 * class only maps them to canvas coordinates. `pixmap`/`pdf` backgrounds render as a plain sheet
 * for now (the referenced image/PDF isn't loaded yet — see `TODO.md`).
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

    fun draw(canvas: Canvas, box: PageBox, scrollY: Float) {
        val top = box.topPx - scrollY
        val solid = box.page.background as? Background.Solid
        fill.color = solid?.color ?: AndroidColor.WHITE
        canvas.drawRect(0f, top, box.widthPx, top + box.heightPx, fill)
        when (solid?.style) {
            "lined" -> horizontals(canvas, box, top)
            "ruled" -> { horizontals(canvas, box, top); marginLine(canvas, box, top) }
            "graph" -> grid(canvas, box, top)
            "dotted" -> dots(canvas, box, top)
            else -> Unit // "plain", unknown, or non-solid: bare sheet
        }
    }

    private fun horizontals(canvas: Canvas, box: PageBox, top: Float) {
        for (y in BackgroundGrid.lines(box.page.height, RULE_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            canvas.drawLine(0f, py, box.widthPx, py, line)
        }
    }

    private fun marginLine(canvas: Canvas, box: PageBox, top: Float) {
        val x = (MARGIN_PT * box.scale).toFloat()
        canvas.drawLine(x, top, x, top + box.heightPx, margin)
    }

    private fun grid(canvas: Canvas, box: PageBox, top: Float) {
        for (y in BackgroundGrid.lines(box.page.height, GRID_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            canvas.drawLine(0f, py, box.widthPx, py, line)
        }
        for (x in BackgroundGrid.lines(box.page.width, GRID_SPACING_PT)) {
            val px = (x * box.scale).toFloat()
            canvas.drawLine(px, top, px, top + box.heightPx, line)
        }
    }

    private fun dots(canvas: Canvas, box: PageBox, top: Float) {
        val radius = (box.scale).coerceIn(1f, 2.5f)
        for (y in BackgroundGrid.lines(box.page.height, GRID_SPACING_PT)) {
            val py = top + (y * box.scale).toFloat()
            for (x in BackgroundGrid.lines(box.page.width, GRID_SPACING_PT)) {
                canvas.drawCircle((x * box.scale).toFloat(), py, radius, dot)
            }
        }
    }
}
