package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
 * A `<teximage>` carries only its LaTeX source in our model (no rendered glyphs), so it is drawn as
 * a best-effort placeholder — a faint box with the source text — until a real LaTeX renderer lands.
 */
class ElementRenderer {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33000000
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bitmapCache = IdentityHashMap<ImageElement, Bitmap?>()

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
        textPaint.typeface = Typeface.create(t.font, Typeface.NORMAL)
        val fm = textPaint.fontMetrics
        val topPx = offsetY + (t.y * scale).toFloat()
        val xPx = offsetX + (t.x * scale).toFloat()
        val lineHeight = fm.descent - fm.ascent
        val lines = TextBlock.lines(t.content)
        val baselines = TextBlock.baselines(lines.size, topPx, fm.ascent, lineHeight)
        for (i in lines.indices) canvas.drawText(lines[i], xPx, baselines[i], textPaint)
    }

    private fun drawImage(canvas: Canvas, img: ImageElement, scale: Float, offsetX: Float, offsetY: Float) {
        val bmp = bitmapCache.getOrPut(img) {
            BitmapFactory.decodeByteArray(img.data, 0, img.data.size)
        } ?: return
        canvas.drawBitmap(bmp, null, rect(img.left, img.top, img.right, img.bottom, scale, offsetX, offsetY), bitmapPaint)
    }

    private fun drawTex(canvas: Canvas, tex: TexImageElement, scale: Float, offsetX: Float, offsetY: Float) {
        val dst = rect(tex.left, tex.top, tex.right, tex.bottom, scale, offsetX, offsetY)
        canvas.drawRect(dst, boxPaint)
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
}
