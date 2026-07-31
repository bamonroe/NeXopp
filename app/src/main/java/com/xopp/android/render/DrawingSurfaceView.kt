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
 * The low-latency stylus canvas. Holds the whole [Document] and renders every page top-to-bottom,
 * each with its background ruling and all its layers, scaled to fit the view width (see
 * [PageStacker] / [BackgroundRenderer]). One finger draws; two fingers scroll the page stack.
 * Strokes are drawn here; text boxes, images, and LaTeX images are drawn by [ElementRenderer].
 * Pressure is captured at the fidelity `.xopp` stores — this is where round-trip safety starts
 * (see `docs/architecture.md`).
 */
class DrawingSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** The working document; edits append strokes to it and [toDocument] returns it verbatim. */
    private var doc: Document = Document(pages = listOf(blankPage()))
    private var layout: StackedLayout = StackedLayout(emptyList(), 0f)
    private var scrollY = 0f

    /** In-progress stroke (page-local pt space) and the page it belongs to. */
    private var current: ArrayList<StrokePoint>? = null
    private var currentPage = 0
    private var scrolling = false
    private var lastFocusY = 0f

    var tool: Tool = Tool.PEN
    var colorArgb: Int = AndroidColor.BLACK
    var baseWidthPt: Float = 1.5f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val elementRenderer = ElementRenderer()

    init {
        holder.addCallback(this)
    }

    /** Replace the canvas contents with [doc] (all pages, layers, and unmodelled elements). */
    fun load(doc: Document) {
        this.doc = if (doc.pages.isEmpty()) doc.copy(pages = listOf(blankPage())) else doc
        scrollY = 0f
        relayout()
        render()
    }

    /** The current working document — every page, layer, and preserved element, ready to save. */
    fun toDocument(): Document = doc

    // --- touch: one finger draws, two fingers scroll -------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startStroke(event)
            MotionEvent.ACTION_POINTER_DOWN -> beginScroll(event)
            MotionEvent.ACTION_MOVE -> if (scrolling) doScroll(event) else extendStroke(event)
            MotionEvent.ACTION_POINTER_UP -> lastFocusY = focusY(event, skip = event.actionIndex)
            MotionEvent.ACTION_UP -> endGesture()
            MotionEvent.ACTION_CANCEL -> { current = null; scrolling = false }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun startStroke(event: MotionEvent) {
        scrolling = false
        val box = layout.pageAt(event.y + scrollY) ?: run { current = null; return }
        currentPage = box.index
        current = ArrayList<StrokePoint>().also { addSamples(event, box, it) }
    }

    private fun extendStroke(event: MotionEvent) {
        val box = layout.boxes.getOrNull(currentPage) ?: return
        current?.let { addSamples(event, box, it); render() }
    }

    /** A second finger touched down: abandon any partial stroke and switch to scrolling. */
    private fun beginScroll(event: MotionEvent) {
        current = null
        scrolling = true
        lastFocusY = focusY(event, skip = -1)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = focusY(event, skip = -1)
        val maxScroll = (layout.totalHeightPx - height).coerceAtLeast(0f)
        scrollY = (scrollY + (lastFocusY - fy)).coerceIn(0f, maxScroll)
        lastFocusY = fy
        render()
    }

    private fun endGesture() {
        if (!scrolling) commitCurrent()
        scrolling = false
    }

    /** Mean Y of all pointers except [skip] (an index being lifted), in view px. */
    private fun focusY(event: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) if (i != skip) { sum += event.getY(i); n++ }
        return if (n == 0) event.y else sum / n
    }

    /** Append every sample in this event — historical first — as pressure-scaled page-local points. */
    private fun addSamples(event: MotionEvent, box: PageBox, into: MutableList<StrokePoint>) {
        for (h in 0 until event.historySize) {
            into += point(box, event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h))
        }
        into += point(box, event.x, event.y, event.pressure)
    }

    private fun point(box: PageBox, vx: Float, vy: Float, pressure: Float): StrokePoint {
        val p = if (pressure <= 0f) 1f else pressure
        val contentY = vy + scrollY
        return StrokePoint(
            x = (vx / box.scale).toDouble(),
            y = ((contentY - box.topPx) / box.scale).toDouble(),
            width = (baseWidthPt * (0.4f + 0.6f * p)).toDouble(),
        )
    }

    private fun commitCurrent() {
        val pts = current ?: return
        current = null
        if (pts.size >= 2) appendStroke(currentPage, Stroke(tool, strokeColor(), "round", pts, uniformWidth = false))
        render()
    }

    /** Highlighter is stored semi-transparent so it round-trips (and renders) translucent. */
    private fun strokeColor(): Int =
        if (tool == Tool.HIGHLIGHTER && (colorArgb ushr 24) == 0xFF) {
            (colorArgb and 0x00FFFFFF) or 0x80000000.toInt()
        } else {
            colorArgb
        }

    /** Append [stroke] to the top (last) layer of page [pageIndex], rebuilding the model. */
    private fun appendStroke(pageIndex: Int, stroke: Stroke) {
        val pages = doc.pages.toMutableList()
        val page = pages[pageIndex]
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val top = layers.lastIndex
        layers[top] = Layer(layers[top].elements + stroke)
        pages[pageIndex] = page.copy(layers = layers)
        doc = doc.copy(pages = pages)
        relayout() // rebuild boxes so they reference the updated pages, not stale ones
    }

    // --- surface + rendering -------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        relayout(); render()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun relayout() {
        layout = PageStacker.stack(doc.pages, width, GAP_PX)
        val maxScroll = (layout.totalHeightPx - height).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    private fun render() {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(BACKDROP)
            for (box in layout.visible(scrollY, height.toFloat())) {
                BackgroundRenderer.draw(canvas, box, scrollY)
                drawPageElements(canvas, box)
            }
            drawCurrent(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawPageElements(canvas: Canvas, box: PageBox) {
        val offsetY = box.topPx - scrollY
        for (layer in box.page.layers) {
            for (element in layer.elements) {
                if (element is Stroke) {
                    drawPoints(canvas, element.points, element.tool, element.color, box.scale, offsetY)
                } else {
                    elementRenderer.draw(canvas, element, box.scale, offsetY)
                }
            }
        }
    }

    private fun drawCurrent(canvas: Canvas) {
        val pts = current ?: return
        val box = layout.boxes.getOrNull(currentPage) ?: return
        drawPoints(canvas, pts, tool, strokeColor(), box.scale, box.topPx - scrollY)
    }

    private fun drawPoints(canvas: Canvas, pts: List<StrokePoint>, tool: Tool, color: Int, scale: Float, offsetY: Float) {
        if (pts.size < 2) return
        paint.color = renderColor(tool, color)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = ((a.width + b.width) / 2.0).toFloat() * scale
            canvas.drawLine(
                (a.x * scale).toFloat(), offsetY + (a.y * scale).toFloat(),
                (b.x * scale).toFloat(), offsetY + (b.y * scale).toFloat(),
                paint,
            )
        }
    }

    /** Highlighter always paints translucent even if the stored colour is opaque. */
    private fun renderColor(tool: Tool, color: Int): Int =
        if (tool == Tool.HIGHLIGHTER && (color ushr 24) == 0xFF) (color and 0x00FFFFFF) or 0x80000000.toInt() else color

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val GAP_PX = 24f
        const val BACKDROP = 0xFF3A3A3A.toInt()

        fun blankPage() = Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(AndroidColor.WHITE, "graph"), listOf(Layer(emptyList())))
    }
}
