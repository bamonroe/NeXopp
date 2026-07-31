package com.xopp.android.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a parsed [LatexNode] tree (from [LatexParser]) onto an Android [Canvas].
 *
 * This is the Android-dependent half of the LaTeX renderer: it measures glyph runs with a
 * [Paint] and lays out fractions (numerator over denominator with a rule), super/subscripts
 * (smaller and shifted), and square roots (a radical sign plus a vinculum over the radicand).
 *
 * Layout is done at a fixed reference font size and then uniformly scaled to fit the target
 * rectangle: glyph metrics scale linearly with text size, so we can measure the tree's natural
 * proportions once and pick the largest font that fits both the width and the height.
 */
class LatexRenderer {

    /** Natural width, and heights above/below the baseline (all px at [fontSize]). */
    private class Metrics(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = ascent + descent
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val radicalPath = Path()

    private companion object {
        const val REF = 64f            // reference font size used for the fit measurement
        const val SCRIPT_RATIO = 0.7f  // super/subscript font shrink factor
        const val MIN_FONT = 6f
    }

    /**
     * Measure [node] and draw it centered inside [bounds] (px), tinted [color], scaled up to at
     * most [maxFontSize]. Draws nothing for an empty/zero-size tree. Never throws for well-formed
     * trees; the caller still guards with a try/catch for defense in depth.
     */
    fun draw(canvas: Canvas, node: LatexNode, bounds: RectF, color: Int, maxFontSize: Float) {
        val natural = measure(node, REF)
        if (natural.width <= 0f || natural.height <= 0f) return
        val fit = min(bounds.width() / natural.width, bounds.height() / natural.height)
        val fontSize = min(REF * fit, maxFontSize).coerceAtLeast(MIN_FONT)
        val m = measure(node, fontSize)
        textPaint.color = color
        rulePaint.color = color
        rulePaint.strokeWidth = max(1f, fontSize * 0.045f)
        val startX = bounds.left + (bounds.width() - m.width) / 2f
        val baselineY = bounds.top + (bounds.height() - m.height) / 2f + m.ascent
        drawNode(canvas, node, startX, baselineY, fontSize)
    }

    // --- Measurement ---------------------------------------------------------------------

    private fun measure(node: LatexNode, fontSize: Float): Metrics = when (node) {
        is Row -> measureRow(node, fontSize)
        is SymbolRun -> measureSymbol(node, fontSize)
        is Superscript -> measureScript(node.base, node.sup, null, fontSize)
        is Subscript -> measureScript(node.base, null, node.sub, fontSize)
        is SubSup -> measureScript(node.base, node.sup, node.sub, fontSize)
        is Fraction -> measureFraction(node, fontSize)
        is Sqrt -> measureSqrt(node, fontSize)
    }

    private fun measureSymbol(node: SymbolRun, fontSize: Float): Metrics {
        textPaint.textSize = fontSize
        val fm = textPaint.fontMetrics
        return Metrics(textPaint.measureText(node.text), -fm.ascent, fm.descent)
    }

    private fun measureRow(row: Row, fontSize: Float): Metrics {
        if (row.children.isEmpty()) return Metrics(0f, 0f, 0f)
        var w = 0f; var asc = 0f; var desc = 0f
        for (child in row.children) {
            val m = measure(child, fontSize)
            w += m.width; asc = max(asc, m.ascent); desc = max(desc, m.descent)
        }
        return Metrics(w, asc, desc)
    }

    private fun measureScript(base: LatexNode, sup: LatexNode?, sub: LatexNode?, fontSize: Float): Metrics {
        val b = measure(base, fontSize)
        val sf = fontSize * SCRIPT_RATIO
        var asc = b.ascent; var desc = b.descent; var scriptW = 0f
        if (sup != null) {
            val s = measure(sup, sf); scriptW = max(scriptW, s.width)
            asc = max(asc, fontSize * 0.42f + s.ascent)
        }
        if (sub != null) {
            val s = measure(sub, sf); scriptW = max(scriptW, s.width)
            desc = max(desc, fontSize * 0.16f + s.descent)
        }
        return Metrics(b.width + scriptW, asc, desc)
    }

    private fun measureFraction(f: Fraction, fontSize: Float): Metrics {
        val n = measure(f.num, fontSize); val d = measure(f.den, fontSize)
        val gap = fontSize * 0.15f; val axis = fontSize * 0.28f
        val width = max(n.width, d.width) + fontSize * 0.3f
        return Metrics(width, axis + gap + n.height, max(0f, d.height + gap - axis))
    }

    private fun measureSqrt(s: Sqrt, fontSize: Float): Metrics {
        val r = measure(s.radicand, fontSize)
        val over = fontSize * 0.15f
        return Metrics(fontSize * 0.7f + r.width + fontSize * 0.1f, r.ascent + over, r.descent)
    }

    // --- Drawing (returns the advance width consumed) ------------------------------------

    private fun drawNode(canvas: Canvas, node: LatexNode, x: Float, baselineY: Float, fontSize: Float): Float =
        when (node) {
            is Row -> drawRow(canvas, node, x, baselineY, fontSize)
            is SymbolRun -> drawSymbol(canvas, node, x, baselineY, fontSize)
            is Superscript -> drawScript(canvas, node.base, node.sup, null, x, baselineY, fontSize)
            is Subscript -> drawScript(canvas, node.base, null, node.sub, x, baselineY, fontSize)
            is SubSup -> drawScript(canvas, node.base, node.sup, node.sub, x, baselineY, fontSize)
            is Fraction -> drawFraction(canvas, node, x, baselineY, fontSize)
            is Sqrt -> drawSqrt(canvas, node, x, baselineY, fontSize)
        }

    private fun drawSymbol(canvas: Canvas, node: SymbolRun, x: Float, baselineY: Float, fontSize: Float): Float {
        textPaint.textSize = fontSize
        canvas.drawText(node.text, x, baselineY, textPaint)
        return textPaint.measureText(node.text)
    }

    private fun drawRow(canvas: Canvas, row: Row, x: Float, baselineY: Float, fontSize: Float): Float {
        var cursor = x
        for (child in row.children) cursor += drawNode(canvas, child, cursor, baselineY, fontSize)
        return cursor - x
    }

    private fun drawScript(
        canvas: Canvas, base: LatexNode, sup: LatexNode?, sub: LatexNode?,
        x: Float, baselineY: Float, fontSize: Float,
    ): Float {
        val baseW = drawNode(canvas, base, x, baselineY, fontSize)
        val sf = fontSize * SCRIPT_RATIO
        var scriptW = 0f
        if (sup != null) scriptW = max(scriptW, drawNode(canvas, sup, x + baseW, baselineY - fontSize * 0.42f, sf))
        if (sub != null) scriptW = max(scriptW, drawNode(canvas, sub, x + baseW, baselineY + fontSize * 0.16f, sf))
        return baseW + scriptW
    }

    private fun drawFraction(canvas: Canvas, f: Fraction, x: Float, baselineY: Float, fontSize: Float): Float {
        val n = measure(f.num, fontSize); val d = measure(f.den, fontSize)
        val gap = fontSize * 0.15f; val axis = fontSize * 0.28f
        val width = max(n.width, d.width) + fontSize * 0.3f
        val cx = x + width / 2f
        val barY = baselineY - axis
        drawNode(canvas, f.num, cx - n.width / 2f, barY - gap - n.descent, fontSize)
        drawNode(canvas, f.den, cx - d.width / 2f, barY + gap + d.ascent, fontSize)
        canvas.drawLine(x + fontSize * 0.05f, barY, x + width - fontSize * 0.05f, barY, rulePaint)
        return width
    }

    private fun drawSqrt(canvas: Canvas, s: Sqrt, x: Float, baselineY: Float, fontSize: Float): Float {
        val r = measure(s.radicand, fontSize)
        val over = fontSize * 0.15f
        val symW = fontSize * 0.7f
        val width = symW + r.width + fontSize * 0.1f
        val topY = baselineY - r.ascent - over
        val bottomY = baselineY + r.descent
        radicalPath.reset()
        radicalPath.moveTo(x, baselineY - r.ascent * 0.35f)
        radicalPath.lineTo(x + symW * 0.35f, bottomY)
        radicalPath.lineTo(x + symW * 0.7f, topY)
        radicalPath.lineTo(x + width, topY)
        canvas.drawPath(radicalPath, rulePaint)
        drawNode(canvas, s.radicand, x + symW, baselineY, fontSize)
        return width
    }
}
