package com.xopp.android.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool

/**
 * The low-latency stylus canvas. Captures raw [MotionEvent] pressure (including historical
 * samples, so fast strokes keep their points) and renders strokes in page/pt space scaled to
 * fit the view width. This is where "round-trip safety" starts — we record pressure at the
 * fidelity the `.xopp` format stores. See `docs/architecture.md`.
 */
class DrawingSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Committed strokes in page (pt) space, oldest first. */
    private val strokes = ArrayList<Stroke>()
    private var current: ArrayList<StrokePoint>? = null

    private var pageWidthPt = A4_WIDTH_PT
    private var pageHeightPt = A4_HEIGHT_PT
    private var scale = 1f

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        holder.addCallback(this)
    }

    /** Replace the canvas contents with the first page of [doc]. */
    fun load(doc: Document) {
        val page = doc.pages.firstOrNull()
        if (page != null) {
            pageWidthPt = page.width.toFloat()
            pageHeightPt = page.height.toFloat()
            strokes.clear()
            page.layers.flatMap { it.elements }.filterIsInstance<Stroke>().forEach(strokes::add)
        }
        recomputeScale()
        render()
    }

    /** Snapshot the canvas as a single-page, single-layer [Document]. */
    fun toDocument(): Document = Document(
        pages = listOf(
            Page(
                width = pageWidthPt.toDouble(),
                height = pageHeightPt.toDouble(),
                background = Background.Solid(AndroidColor.WHITE, "graph"),
                layers = listOf(Layer(strokes.toList())),
            ),
        ),
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = ArrayList<StrokePoint>().also { addSamples(event, it) }
            MotionEvent.ACTION_MOVE -> current?.let { addSamples(event, it); render() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> commitCurrent()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** Append every sample in this event — historical first — as pressure-scaled points. */
    private fun addSamples(event: MotionEvent, into: MutableList<StrokePoint>) {
        for (h in 0 until event.historySize) {
            into += point(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h))
        }
        into += point(event.x, event.y, event.pressure)
    }

    private fun point(vx: Float, vy: Float, pressure: Float): StrokePoint {
        val p = if (pressure <= 0f) 1f else pressure
        return StrokePoint(
            x = (vx / scale).toDouble(),
            y = (vy / scale).toDouble(),
            width = (baseWidthPt * (0.4f + 0.6f * p)).toDouble(),
        )
    }

    private fun commitCurrent() {
        val pts = current ?: return
        current = null
        if (pts.size >= 2) {
            strokes += Stroke(tool, colorArgb, "round", pts, uniformWidth = false)
        }
        render()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        recomputeScale(); render()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun recomputeScale() {
        if (width > 0 && pageWidthPt > 0) scale = width / pageWidthPt
    }

    private fun render() {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(AndroidColor.WHITE)
            strokes.forEach { drawStroke(canvas, it) }
            current?.let { drawPoints(canvas, it, tool, colorArgb) }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) = drawPoints(canvas, s.points, s.tool, s.color)

    private fun drawPoints(canvas: Canvas, pts: List<StrokePoint>, tool: Tool, color: Int) {
        if (pts.size < 2) return
        paint.color = if (tool == Tool.HIGHLIGHTER) (color and 0x00FFFFFF) or 0x66000000 else color
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = ((a.width + b.width) / 2.0).toFloat() * scale
            canvas.drawLine(
                (a.x * scale).toFloat(), (a.y * scale).toFloat(),
                (b.x * scale).toFloat(), (b.y * scale).toFloat(),
                paint,
            )
        }
    }

    private companion object {
        const val A4_WIDTH_PT = 595.276f
        const val A4_HEIGHT_PT = 841.89f
    }
}
