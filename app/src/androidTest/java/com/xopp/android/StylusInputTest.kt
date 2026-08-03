package com.xopp.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.InputSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the stylus input layer (see `docs/architecture.md` → "Stylus &
 * selection roadmap"): the eraser tip, the finger-draw palm gate, the barrel button, and palm
 * rejection while a stylus writes. These need real Android `MotionEvent`s carrying a *tool type* and
 * *button state* (which `adb input` can't inject and JVM unit tests can't build), so they live here
 * and run via `connectedDebugAndroidTest`. The pure decision logic is unit-tested in
 * `InputClassifierTest`; this proves the view is wired to it.
 */
@RunWith(AndroidJUnit4::class)
class StylusInputTest {

    /** The eraser tip erases a stroke even though the selected tool is the pen. */
    @Test
    fun eraserTipErasesWithPenSelected() = onView { view ->
        view.tool = Tool.PEN
        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals("pen laid down a stroke", 1, strokesOf(view).size)

        // Same path, but with the flipped-over eraser tip: it should delete the stroke.
        drawLine(view, MotionEvent.TOOL_TYPE_ERASER)
        assertEquals("eraser tip removed the stroke", 0, strokesOf(view).size)
    }

    /** The barrel button erases while held, regardless of the pen being selected (default mapping). */
    @Test
    fun barrelButtonErasesWhileHeld() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(barrelAction = BarrelAction.ERASE)
        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(1, strokesOf(view).size)

        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS, buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals("barrel-held stylus erased the stroke", 0, strokesOf(view).size)
    }

    /** With finger-draw off, a finger only pans — it must not lay ink; a stylus still draws. */
    @Test
    fun fingerDrawOffStopsFingerButNotStylus() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(fingerDraws = false)

        drawLine(view, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("finger did not draw", 0, strokesOf(view).size)

        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals("stylus still draws", 1, strokesOf(view).size)
    }

    /** A palm (finger) resting mid-stroke while the stylus writes is ignored, not drawn/panned. */
    @Test
    fun palmRestingDuringStylusStrokeIsIgnored() = onView { view ->
        view.tool = Tool.PEN
        val downTime = SystemClock.uptimeMillis()

        // Stylus tip goes down and moves right.
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(200f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        send(view, downTime, now(), MotionEvent.ACTION_MOVE, floatArrayOf(260f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))

        // A palm lands as a second pointer and moves — it must not perturb the stroke.
        send(view, downTime, now(), MotionEvent.ACTION_POINTER_DOWN,
            floatArrayOf(260f, 850f), floatArrayOf(300f, 900f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER), actionIndex = 1)
        send(view, downTime, now(), MotionEvent.ACTION_MOVE,
            floatArrayOf(320f, 860f), floatArrayOf(300f, 910f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER))
        // Palm lifts, then the stylus lifts.
        send(view, downTime, now(), MotionEvent.ACTION_POINTER_UP,
            floatArrayOf(320f, 860f), floatArrayOf(300f, 910f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER), actionIndex = 1)
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(320f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))

        val strokes = strokesOf(view)
        assertEquals("exactly one stroke, from the stylus only", 1, strokes.size)
        // The palm was far below (view y≈900); its page-y would be large. Every sampled point must
        // come from the stylus's own path (y≈300 view px → small page y), proving the palm was ignored.
        val maxY = strokes[0].points.maxOf { it.y }
        assertTrue("no palm samples leaked into the stroke (maxY=$maxY)", maxY < 200.0)
    }

    /**
     * The same page-space gesture keeps the same detail whether it's drawn at 100% or zoomed out.
     *
     * Decimation and simplification were both budgeted purely in *view pixels*, so zooming out
     * multiplied how much page detail they threw away — at overview magnification a stored vertex
     * stood for several times as much real pen movement, which is the "corners and jumps" this
     * test guards against.
     */
    @Test
    fun sameGestureKeepsItsDetailAtEveryZoom() = onView { view ->
        view.tool = Tool.PEN

        val wide = drawRipple(view, zoom = 1f)
        // Six zoom-out steps ≈ 26% — the magnification of a multi-page overview.
        var zoomed = 1f
        repeat(6) { view.zoomOut(); zoomed /= ZOOM_STEP }
        val narrow = drawRipple(view, zoom = zoomed)

        assertTrue("100% gesture produced points", wide.size >= 8)
        assertTrue("zoomed-out gesture produced points", narrow.size >= 8)

        // Both strokes traced the same ripple on the page, so they should store a comparable
        // number of vertices and cover a comparable page-space distance. With the budgets pinned to
        // view pixels the zoomed-out stroke kept barely a third of the vertices; with them bounded
        // in page points it keeps over half (the rest is the simplifier's own page-point cap).
        assertTrue(
            "zoomed-out kept ${narrow.size} vertices vs ${wide.size} at 100%",
            narrow.size >= wide.size / 2,
        )
        val wideLen = lengthPt(wide)
        val narrowLen = lengthPt(narrow)
        assertTrue(
            "zoomed-out path length ${narrowLen}pt vs 100% ${wideLen}pt",
            narrowLen >= wideLen * 0.95,
        )
    }

    // --- harness -------------------------------------------------------------------------------

    /**
     * Trace a fine ripple defined in **page points**, so the gesture is the same real movement at
     * every [zoom], and feed it as dense batches (only a batch's newest sample is force-kept, so
     * this exercises the decimation path the way a real digitiser does).
     */
    private fun drawRipple(view: DrawingSurfaceView, zoom: Float): List<StrokePoint> {
        val before = strokesOf(view).size
        // Page → view, mirroring PageStacker.stack: a single column fits the page to the view
        // width, rows are centred horizontally, and the first row starts one gap down.
        val scale = (VIEW_W / A4_WIDTH_PT).toFloat() * zoom
        val left = (maxOf(VIEW_W.toFloat(), A4_WIDTH_PT.toFloat() * scale) - A4_WIDTH_PT.toFloat() * scale) / 2f
        val top = GAP_PX
        // 0.4pt per sample along 200pt, rippling ±2.5pt every 10pt — detail a fixed 1.6 view-px
        // decimation radius keeps at 100% (0.9pt) but erases when zoomed out (3.4pt).
        fun pageX(i: Int) = 100.0 + i * 0.4
        fun pageY(i: Int) = 300.0 + kotlin.math.sin(pageX(i) * 2 * Math.PI / 10.0) * 2.5
        fun px(i: Int) = left + (pageX(i) * scale).toFloat()
        fun py(i: Int) = top + (pageY(i) * scale).toFloat()

        val downTime = SystemClock.uptimeMillis()
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(px(0)), floatArrayOf(py(0)), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        var i = 1
        while (i < 500) {
            sendBatch(view, downTime, MotionEvent.ACTION_MOVE, (i until i + 10).map { px(it) to py(it) })
            i += 10
        }
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(px(i)), floatArrayOf(py(i)), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))

        val strokes = strokesOf(view)
        assertEquals("the ripple committed one stroke", before + 1, strokes.size)
        return strokes.last().points
    }

    /** One [MotionEvent] carrying [samples] as historical samples plus a current one. */
    private fun sendBatch(view: View, downTime: Long, action: Int, samples: List<Pair<Float, Float>>) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        fun coordsAt(s: Pair<Float, Float>) = arrayOf(
            MotionEvent.PointerCoords().apply { x = s.first; y = s.second; pressure = 1f; size = 1f },
        )
        val event = MotionEvent.obtain(
            downTime, now(), action, 1, props, coordsAt(samples.first()),
            0, 0, 1f, 1f, 0, 0, 0x00004002 /* SOURCE_STYLUS */, 0,
        )
        try {
            for (s in samples.drop(1)) event.addBatch(now(), coordsAt(s), 0)
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** Total page-space length of the stored polyline — how much of the real path survived. */
    private fun lengthPt(points: List<StrokePoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }

    private fun onView(body: (DrawingSurfaceView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = DrawingSurfaceView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            view.load(blankDocument())
            body(view)
        }
        instrumentation.waitForIdleSync()
    }

    /** Down -> a few moves -> up, all with the given [toolType] (and optional held [buttonState]). */
    private fun drawLine(view: DrawingSurfaceView, toolType: Int, buttonState: Int = 0) {
        val downTime = SystemClock.uptimeMillis()
        var x = 200f
        val y = 300f
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
        repeat(5) {
            x += 30f
            send(view, downTime, now(), MotionEvent.ACTION_MOVE, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
        }
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
    }

    /** Build and dispatch a multi-pointer [MotionEvent] with explicit tool types and button state. */
    private fun send(
        view: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        toolTypes: IntArray,
        buttonState: Int = 0,
        actionIndex: Int = 0,
    ) {
        val n = xs.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply { id = i; this.toolType = toolTypes[i] }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i]; pressure = 1f; size = 1f }
        }
        val maskedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(
            downTime, eventTime, maskedAction, n, props, coords,
            0, buttonState, 1f, 1f, 0, 0, 0x00004002 /* InputDevice.SOURCE_STYLUS */, 0,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun strokesOf(view: DrawingSurfaceView): List<Stroke> =
        view.toDocument().pages.flatMap { p -> p.layers.flatMap { it.elements } }.filterIsInstance<Stroke>()

    private fun blankDocument(): Document =
        Document(
            pages = listOf(
                Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(0xFFFFFFFF.toInt(), "graph"), listOf(Layer(emptyList()))),
            ),
        )

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        /** Mirrors `DrawingSurfaceView.ZOOM_STEP`, which is private to the view. */
        const val ZOOM_STEP = 1.25f
        /** Mirrors `DrawingSurfaceView.GAP_PX`. */
        const val GAP_PX = 24f
        const val VIEW_W = 1080
        const val VIEW_H = 1920
    }
}
